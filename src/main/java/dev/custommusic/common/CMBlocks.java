package dev.custommusic.common;

import dev.custommusic.CustomMusicMod;
import dev.custommusic.server.CustomJukeboxBlock;
import dev.custommusic.server.CustomJukeboxBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The Juke Box block — a server-authoritative positional music player with a right-click
 * menu (track picker, loop toggle, volume and hearing-distance sliders).
 *
 * <p>It deliberately cannot be pushed by pistons ({@link PushReaction#BLOCK}): the sound is
 * tied to the block position, and a moving source of looping audio is a desync waiting to
 * happen.</p>
 */
public final class CMBlocks {
    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(Registries.BLOCK, CustomMusicMod.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CustomMusicMod.MODID);

    public static final DeferredHolder<Block, ? extends Block> JUKEBOX = BLOCKS.register("jukebox",
        () -> new CustomJukeboxBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_BROWN)
            .strength(1.5f, 6.0f)
            .sound(SoundType.WOOD)
            .pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CustomJukeboxBlockEntity>> JUKEBOX_ENTITY =
        BLOCK_ENTITIES.register("jukebox",
            () -> BlockEntityType.Builder.of(CustomJukeboxBlockEntity::new, JUKEBOX.get()).build(null));

    private CMBlocks() {
    }
}
