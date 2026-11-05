package dev.custommusic.server;

import com.mojang.logging.LogUtils;
import dev.custommusic.common.CMBlocks;
import dev.custommusic.common.TrackEntry;
import dev.custommusic.common.payload.JukeboxPayloads;
import dev.custommusic.common.payload.JukeboxPayloads.JukeboxStatePayload;
import dev.custommusic.common.payload.JukeboxPayloads.OpenJukeboxPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Juke Box player. The server owns the state (track, playing, loop, volume, range) and
 * pushes it to clients as {@link JukeboxStatePayload}s:
 *
 * <ul>
 *   <li><b>start</b> — to every player inside {@code range} that does not own a live sound
 *       instance for this block yet (the 0.5 s listener sweep in {@link #serverTick});</li>
 *   <li><b>restart</b> — to players that already own one whenever track/loop/volume/range
 *       change (the client restarts the instance; the vanilla sound engine has no live seek);</li>
 *   <li><b>stop</b> — to everyone notified when playback stops (break, Stop button, natural
 *       end of a non-looping track — the server knows every track's exact duration).</li>
 * </ul>
 *
 * <p>Players who walk out of range are NOT sent anything: the client-side instance is created
 * with the block's hearing distance baked in (see {@code JukeboxSoundFactory}), so it fades to
 * silence exactly at {@code range} blocks and fades back in if they return. Looping is handled
 * natively by the client sound instance ({@code LoopingAudioStream}), so a looped track costs
 * the server nothing.</p>
 *
 * <p>There is no {@code BlockEntity#onLoad} in 1.21.1 — the loaded-jukebox registry is fed
 * from {@link #setLevel} (fresh placement) and the ChunkEvent.Load/Unload subscribers in
 * {@code ServerEvents}.</p>
 */
public class CustomJukeboxBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** All loaded jukeboxes — used to forget players on (re)login and to resync after /customdisc reload. */
    private static final Set<CustomJukeboxBlockEntity> LOADED = ConcurrentHashMap.newKeySet();

    // Keep in sync with JukeboxScreen's slider bounds (the server clamps anyway).
    private static final float MIN_VOLUME = 0.10f;
    private static final float MAX_VOLUME = 1.00f;
    private static final int MIN_RANGE = 4;
    private static final int MAX_RANGE = 96;
    /** Grace period (seconds) added to the track duration before the server auto-stops. */
    private static final float END_GRACE_SECONDS = 2.0f;

    private String slug = "";
    private boolean playing;
    private boolean loop;
    private float volume = 1.0f;
    private int range = 16;
    /** Game time the current track started at; 0 = needs re-arming (fresh world load). */
    private long startedAtTick;
    /** Players that own a live sound instance for this block (server side bookkeeping only). */
    private final Set<UUID> notified = new HashSet<>();

    public CustomJukeboxBlockEntity(BlockPos pos, BlockState blockState) {
        super(CMBlocks.JUKEBOX_ENTITY.get(), pos, blockState);
    }

    // ---------------------------------------------------------------- state changes (server)

    /** Start playing a track (slug already validated by the caller). Restarts when the track differs. */
    public void play(String newSlug) {
        if (playing && newSlug.equals(slug)) {
            return;
        }
        slug = newSlug;
        playing = true;
        startedAtTick = level != null ? level.getGameTime() : 0;
        setChanged();
        broadcastState(null);
        LOGGER.debug("[CustomMusic] Juke Box at {} now playing '{}'", worldPosition, newSlug);
    }

    /** Stop playback and tell every notified client to stop its instance. */
    public void stop() {
        boolean wasPlaying = playing;
        playing = false;
        setChanged();
        broadcastState(null); // playing=false reaches notified ∪ in-range players
        notified.clear();
        if (wasPlaying) {
            LOGGER.debug("[CustomMusic] Juke Box at {} stopped", worldPosition);
        }
    }

    /** Update loop/volume/range; playing clients restart their instance with the new settings. */
    public void applySettings(boolean newLoop, float newVolume, int newRange) {
        boolean changed = newLoop != loop
            || Math.abs(clampVolume(newVolume) - volume) > 0.001f
            || clampRange(newRange) != range;
        if (!changed) {
            return;
        }
        loop = newLoop;
        volume = clampVolume(newVolume);
        range = clampRange(newRange);
        setChanged();
        if (playing) {
            broadcastState(null); // restarts client instances with the new volume/range/loop
        }
    }

    /** Block broken or replaced: stop the sound everywhere, leave the registry. */
    public void stopAndForget() {
        stop();
        LOADED.remove(this);
    }

    /** Chunk (re)loaded: enter the registry; the listener sweep re-notifies in-range players. */
    public void notifyChunkLoaded() {
        LOADED.add(this);
    }

    /**
     * Chunk is unloading: tell players that still own a looping instance to stop it, but keep
     * {@code playing} in the NBT so the track resumes (from the beginning) when the chunk
     * reloads and a listener walks into range.
     */
    public void suspendForUnload() {
        if (playing && !notified.isEmpty()) {
            JukeboxStatePayload stopState =
                new JukeboxStatePayload(worldPosition, slug, false, loop, volume, range);
            for (UUID id : notified) {
                if (level == null) break;
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
                if (player != null) {
                    PacketDistributor.sendToPlayer(player, stopState);
                }
            }
        }
        notified.clear();
        LOADED.remove(this);
    }

    /** Right-click: push a fresh state, then ask the client to open the screen. */
    public void openMenu(ServerPlayer player) {
        pushState(player);
        PacketDistributor.sendToPlayer(player, new OpenJukeboxPayload(worldPosition));
    }

    /** Send the current state to one player (used by the login sweep and openMenu). */
    public void pushState(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, snapshot());
    }

    // ---------------------------------------------------------------- server tick

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                   CustomJukeboxBlockEntity jukebox) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        long now = serverLevel.getGameTime();
        if (jukebox.playing && jukebox.startedAtTick == 0) {
            // World just loaded with playing=true: resume from the beginning of the track.
            jukebox.startedAtTick = now;
        }

        if (jukebox.playing) {
            TrackEntry entry = TrackStore.get().bySlug(jukebox.slug);
            if (entry == null) {
                jukebox.stop(); // track vanished after a rescan — stop cleanly
            } else if (!jukebox.loop
                && now - jukebox.startedAtTick >= (long) ((entry.durationSeconds() + END_GRACE_SECONDS) * 20.0f)) {
                jukebox.stop(); // natural end of a non-looping track
            }
        }

        if (now % 10 != 0) { // listener sweep + particles: every 10 ticks (0.5 s)
            return;
        }
        jukebox.sweepListeners(serverLevel);
        jukebox.spawnNotes(serverLevel);
    }

    /** Tell players that just walked into range to start their (new) sound instance. */
    private void sweepListeners(ServerLevel serverLevel) {
        if (!playing) {
            return;
        }
        double r2 = (double) range * (double) range;
        Vec3 center = Vec3.atCenterOf(worldPosition);
        for (ServerPlayer player : serverLevel.players()) {
            if (!notified.contains(player.getUUID())
                && !player.isSpectator()
                && player.distanceToSqr(center) <= r2) {
                pushState(player);
                notified.add(player.getUUID());
            }
        }
    }

    private void spawnNotes(ServerLevel serverLevel) {
        if (playing && serverLevel.random.nextInt(3) == 0) {
            double x = worldPosition.getX() + 0.5 + (serverLevel.random.nextDouble() - 0.5) * 0.6;
            double y = worldPosition.getY() + 1.05;
            double z = worldPosition.getZ() + 0.5 + (serverLevel.random.nextDouble() - 0.5) * 0.6;
            serverLevel.sendParticles(ParticleTypes.NOTE, x, y, z, 1, 0.0, 0.0, 0.0,
                serverLevel.random.nextFloat());
        }
    }

    /**
     * Send the state to every in-range player plus everyone that already owns a live sound
     * instance (they may have walked away — looping instances must still hear about stops and
     * track changes). {@code actor} (the player who triggered the change) is included too.
     */
    private void broadcastState(ServerPlayer actor) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        JukeboxStatePayload state = snapshot();
        double r2 = (double) range * (double) range;
        Vec3 center = Vec3.atCenterOf(worldPosition);
        for (ServerPlayer player : serverLevel.players()) {
            boolean inRange = !player.isSpectator() && player.distanceToSqr(center) <= r2;
            if (inRange || notified.contains(player.getUUID()) || player == actor) {
                PacketDistributor.sendToPlayer(player, state);
                if (inRange && playing) {
                    notified.add(player.getUUID());
                }
            }
        }
    }

    private JukeboxStatePayload snapshot() {
        return new JukeboxStatePayload(worldPosition, slug, playing, loop, volume, range);
    }

    // ---------------------------------------------------------------- lifecycle bookkeeping

    /** Fresh placement into a (loaded) chunk — the ChunkEvent.Load subscriber covers chunk loads. */
    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level != null && !level.isClientSide()) {
            LOADED.add(this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        LOADED.remove(this);
    }

    /** A player (re)logged in: their client has no sound instances, so all jukeboxes re-notify. */
    public static void forgetPlayer(UUID playerId) {
        for (CustomJukeboxBlockEntity jukebox : LOADED) {
            jukebox.notified.remove(playerId);
        }
    }

    /**
     * After /customdisc reload the slot mapping can change and every client rebuilds its
     * sounds.json (all instances die with the sound engine). Drop the notified bookkeeping so
     * the listener sweep re-pushes fresh state within half a second.
     */
    public static void resyncAll() {
        for (CustomJukeboxBlockEntity jukebox : LOADED) {
            jukebox.notified.clear();
        }
    }

    // ---------------------------------------------------------------- persistence

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("track", slug);
        tag.putBoolean("playing", playing);
        tag.putBoolean("loop", loop);
        tag.putFloat("volume", volume);
        tag.putInt("range", range);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        slug = tag.getString("track");
        loop = tag.getBoolean("loop");
        volume = tag.contains("volume") ? clampVolume(tag.getFloat("volume")) : 1.0f;
        range = tag.contains("range") ? clampRange(tag.getInt("range")) : 16;
        playing = tag.getBoolean("playing") && !slug.isEmpty();
        startedAtTick = 0; // resume from the track beginning on the next server tick
    }

    // ---------------------------------------------------------------- helpers

    public static float clampVolume(float value) {
        return Math.round(Mth.clamp(value, MIN_VOLUME, MAX_VOLUME) * 100.0f) / 100.0f;
    }

    public static int clampRange(int value) {
        return Mth.clamp(value, MIN_RANGE, MAX_RANGE);
    }

    public boolean isPlaying() {
        return playing;
    }

    public String trackSlug() {
        return slug;
    }
}
