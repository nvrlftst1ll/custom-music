package dev.custommusic.server;

import dev.custommusic.common.CMBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Juke Box block. Right-clicking it opens the player menu (track picker, loop toggle,
 * volume and hearing-range sliders) — everything else (sound, range management, auto-stop
 * for non-looping tracks) lives in {@link CustomJukeboxBlockEntity} and its ticker.
 *
 * <p>Server-side only behaviour: {@link #getTicker} returns a server ticker; the client just
 * renders the model and plays instances spawned by the payload-driven sound manager.</p>
 */
public class CustomJukeboxBlock extends BaseEntityBlock {

    public CustomJukeboxBlock(Properties properties) {
        super(properties);
    }

    /** Datapack codec (every vanilla BaseEntityBlock has one; simpleCodec covers plain blocks). */
    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(CustomJukeboxBlock::new);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CustomJukeboxBlockEntity(pos, state);
    }

    /** {@link BaseEntityBlock} hides the model by default (it expects a BlockEntityRenderer). */
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof CustomJukeboxBlockEntity jukebox) {
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                jukebox.openMenu(serverPlayer);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, CMBlocks.JUKEBOX_ENTITY.get(), CustomJukeboxBlockEntity::serverTick);
    }

    /** Stopping the sound on break is important: a looping track would otherwise keep playing. */
    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        if (!oldState.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof CustomJukeboxBlockEntity jukebox) {
            jukebox.stopAndForget();
        }
        super.onRemove(oldState, level, pos, newState, movedByPiston);
    }
}
