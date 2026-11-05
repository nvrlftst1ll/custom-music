package dev.custommusic.client;

import com.mojang.logging.LogUtils;
import dev.custommusic.CustomMusicMod;
import dev.custommusic.common.TrackEntry;
import dev.custommusic.common.payload.JukeboxPayloads.JukeboxStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side Juke Box sound manager. Holds the latest server state per block position and
 * keeps exactly one sound instance per playing Juke Box in step with it:
 *
 * <ul>
 *   <li>new playing state (or settings change) -> (re)create the instance;</li>
 *   <li>stopped state -> stop the instance;</li>
 *   <li>periodic re-reconcile (every 2 s) -> retry tracks that were still downloading when
 *       the state arrived, and clean up instances that died (e.g. after F3+T).</li>
 * </ul>
 *
 * <p>All entry points run on the render/client thread (payload handlers use enqueueWork),
 * so the maps need no synchronization beyond the state snapshot map.</p>
 */
@EventBusSubscriber(modid = CustomMusicMod.MODID, value = Dist.CLIENT)
public final class JukeboxClientManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Re-reconcile interval, in client ticks. */
    private static final int RETICK_INTERVAL = 40;
    private static final float VOLUME_EPSILON = 0.005f;
    private static final int RANGE_EPSILON = 0;

    /** Latest server state per Juke Box position. */
    private static final Map<BlockPos, JukeboxStatePayload> STATES = new ConcurrentHashMap<>();
    /** The live sound instance per position, plus the settings it was created with. */
    private static final Map<BlockPos, LiveSound> SOUNDS = new java.util.HashMap<>();
    private static int tickCounter;
    private static long lastWarnMillis;

    private JukeboxClientManager() {
    }

    /** One sound instance and the exact settings it was started with (for change detection). */
    private record LiveSound(SoundInstance instance, String slug, int slot, boolean loop,
                             float volume, int range) {
    }

    // ---------------------------------------------------------------- payload entry points

    public static void onState(JukeboxStatePayload state) {
        STATES.put(state.pos(), state);
        reconcile(state);
    }

    public static JukeboxStatePayload getState(BlockPos pos) {
        return STATES.get(pos);
    }

    // ---------------------------------------------------------------- reconcile

    private static void reconcile(JukeboxStatePayload state) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = state.pos();
        LiveSound live = SOUNDS.get(pos);

        if (!state.playing()) {
            if (live != null) {
                mc.getSoundManager().stop(live.instance());
                SOUNDS.remove(pos);
            }
            return;
        }

        TrackEntry entry = entryBySlug(state.slug());
        if (entry == null) {
            // Track not synced to this client yet (still downloading or F3+T pending).
            if (live != null) {
                mc.getSoundManager().stop(live.instance());
                SOUNDS.remove(pos);
            }
            warnUnsynced(state.slug());
            return;
        }

        boolean restart = live == null
            || !mc.getSoundManager().isActive(live.instance())
            || !live.slug().equals(state.slug())
            || live.slot() != entry.slot()
            || live.loop() != state.loop()
            || Math.abs(live.volume() - state.volume()) > VOLUME_EPSILON
            || Math.abs(live.range() - state.range()) > RANGE_EPSILON;
        if (!restart) {
            return;
        }

        if (live != null) {
            mc.getSoundManager().stop(live.instance());
        }
        SoundInstance instance = JukeboxSoundFactory.create(entry, state);
        mc.getSoundManager().play(instance);
        SOUNDS.put(pos, new LiveSound(instance, state.slug(), entry.slot(), state.loop(),
            state.volume(), state.range()));
    }

    private static TrackEntry entryBySlug(String slug) {
        for (TrackEntry entry : ClientTrackCache.available()) {
            if (entry.slug().equals(slug)) {
                return entry;
            }
        }
        return null;
    }

    private static void warnUnsynced(String slug) {
        if (System.currentTimeMillis() - lastWarnMillis < 30_000) {
            return;
        }
        lastWarnMillis = System.currentTimeMillis();
        LOGGER.warn("[CustomMusic] Juke Box track '{}' is not synced to this client yet — "
            + "it is either still downloading from the server, or needs a /customdisc reload.", slug);
    }

    // ---------------------------------------------------------------- periodic housekeeping

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            STATES.clear();
            SOUNDS.clear();
            return;
        }
        if (++tickCounter < RETICK_INTERVAL) {
            return;
        }
        tickCounter = 0;
        // Retry states whose track was missing at arrival (downloads finish asynchronously),
        // and drop entries for instances that ended on their own.
        for (JukeboxStatePayload state : STATES.values()) {
            reconcile(state);
        }
        Iterator<Map.Entry<BlockPos, LiveSound>> it = SOUNDS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, LiveSound> e = it.next();
            if (STATES.get(e.getKey()) == null || !mc.getSoundManager().isActive(e.getValue().instance())) {
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        STATES.clear();
        SOUNDS.clear();
    }
}
