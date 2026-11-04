package dev.custommusic.server;

import dev.custommusic.common.payload.CMPayloads.OpenChooserPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The craftable blank disc. Right-click (in air) asks the server to open the track chooser
 * screen on the client; picking a track there bakes it into the disc via a payload.
 */
public class BlankDiscItem extends Item {
    public BlankDiscItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            int count = TrackStore.get().entries().size();
            if (count == 0) {
                player.displayClientMessage(Component.translatable("message.custommusic.no_tracks"), true);
                return InteractionResultHolder.consume(stack);
            }
            PacketDistributor.sendToPlayer(serverPlayer, new OpenChooserPayload(count));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
