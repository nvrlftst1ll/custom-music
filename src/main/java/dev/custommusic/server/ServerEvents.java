package dev.custommusic.server;

import dev.custommusic.CustomMusicMod;
import dev.custommusic.common.commands.CustomDiscCommand;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

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
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TransferQueue.clear(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CustomDiscCommand.register(event.getDispatcher());
    }
}
