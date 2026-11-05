package dev.custommusic.server;

import dev.custommusic.CustomMusicMod;
import dev.custommusic.common.CMItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * Creative-tab registration. {@link BuildCreativeModeTabContentsEvent} is an
 * {@code IModBusEvent}: it MUST be subscribed on the MOD bus — putting it on the
 * game bus crashes mod loading with "IModBusEvent events are not allowed on the
 * common NeoForge bus!".
 */
@EventBusSubscriber(modid = CustomMusicMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class CreativeTabEvents {
    /**
     * The vanilla field {@code CreativeModeTabs.TOOLS_AND_UTILITIES} is private in the
     * production jar (NeoForge only widens it via runtime access transformers), so the
     * key is rebuilt here — identical to the vanilla one, usable everywhere.
     */
    private static final ResourceKey<CreativeModeTab> TOOLS_AND_UTILITIES_TAB =
        ResourceKey.create(Registries.CREATIVE_MODE_TAB,
            ResourceLocation.withDefaultNamespace("tools_and_utilities"));

    private CreativeTabEvents() {
    }

    @SubscribeEvent
    public static void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (TOOLS_AND_UTILITIES_TAB.equals(event.getTabKey())) {
            event.accept(CMItems.BLANK_DISC.get());
        }
    }
}
