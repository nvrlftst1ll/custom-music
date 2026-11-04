package dev.custommusic.server;

import dev.custommusic.common.TrackEntry;
import dev.custommusic.common.payload.CMPayloads.BakeTrackPayload;
import dev.custommusic.common.payload.CMPayloads.RequestTracksPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-side handlers for client payloads. Everything is validated and executed on the
 * main server thread via {@link IPayloadContext#enqueueWork}.
 */
public final class ServerPayloadHandlers {
    private ServerPayloadHandlers() {
    }

    public static void handleRequest(RequestTracksPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        context.enqueueWork(() -> TransferQueue.request(player, payload.slugs()));
    }

    public static void handleBake(BakeTrackPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        context.enqueueWork(() -> bake(player, payload.slug()));
    }

    private static void bake(ServerPlayer player, String slug) {
        TrackEntry entry = TrackStore.get().bySlug(slug);
        if (entry == null) return; // never trust the client

        ItemStack blank = null;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            if (held.is(dev.custommusic.common.CMItems.BLANK_DISC.get())) {
                blank = held;
                break;
            }
        }
        if (blank == null) return;

        ItemStack disc = TrackStore.get().makeDisc(entry, player.server);
        blank.shrink(1);
        if (!player.getInventory().add(disc)) {
            player.drop(disc, false);
        }
        player.displayClientMessage(Component.translatable("message.custommusic.baked", entry.title()), true);
        player.level().playSound(null, player.blockPosition(),
            SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.7f, 1.2f);
    }
}
