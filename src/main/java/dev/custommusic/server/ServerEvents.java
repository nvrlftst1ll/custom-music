package dev.custommusic.server;

import dev.custommusic.CustomMusicMod;
import dev.custommusic.common.commands.CustomDiscCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Game-bus events on the server side (also runs for the integrated server in singleplayer).
 * NB: MOD-bus events (like BuildCreativeModeTabContentsEvent) live in {@link CreativeTabEvents} —
 * mixing them onto this bus crashes mod loading.
 */
@EventBusSubscriber(modid = CustomMusicMod.MODID)
public final class ServerEvents {
    private ServerEvents() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TransferQueue.sendTrackList(player);
            // A fresh client owns no sound instances: all loaded jukeboxes re-notify it.
            CustomJukeboxBlockEntity.forgetPlayer(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TransferQueue.clear(event.getEntity().getUUID());
        CustomJukeboxBlockEntity.forgetPlayer(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CustomDiscCommand.register(event.getDispatcher());
    }

    // 1.21.1 has no BlockEntity#onLoad — the Juke Box registry is fed from the chunk
    // lifecycle instead (fresh placements additionally register via setLevel).
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide() || !(event.getChunk() instanceof LevelChunk chunk)) return;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof CustomJukeboxBlockEntity jukebox) {
                jukebox.notifyChunkLoaded();
            }
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide() || !(event.getChunk() instanceof LevelChunk chunk)) return;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof CustomJukeboxBlockEntity jukebox) {
                jukebox.suspendForUnload();
            }
        }
    }
}
