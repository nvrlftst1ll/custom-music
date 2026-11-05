package dev.custommusic.common;

import dev.custommusic.CustomMusicMod;
import dev.custommusic.server.BlankDiscItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The mod's items (items cannot be registered at runtime):
 *  - {@code custommusic:music_disc}  — the disc given by /customdisc give; the actual track is
 *    stored in the {@code minecraft:jukebox_playable} item component, exactly like vanilla discs.
 *  - {@code custommusic:blank_disc}  — craftable empty disc; right-click opens the track chooser.
 *  - {@code custommusic:jukebox}    — the Juke Box block item.
 */
public final class CMItems {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(Registries.ITEM, CustomMusicMod.MODID);

    public static final DeferredHolder<Item, Item> MUSIC_DISC = ITEMS.register("music_disc",
        () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    public static final DeferredHolder<Item, Item> BLANK_DISC = ITEMS.register("blank_disc",
        () -> new BlankDiscItem(new Item.Properties().stacksTo(1)));

    /** The Juke Box block item (name and behaviour come from the block itself). */
    public static final DeferredHolder<Item, Item> JUKEBOX = ITEMS.register("jukebox",
        () -> new BlockItem(CMBlocks.JUKEBOX.get(), new Item.Properties()));

    private CMItems() {
    }
}
