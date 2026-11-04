package dev.custommusic;

import dev.custommusic.common.CMConfig;
import dev.custommusic.common.CMItems;
import dev.custommusic.common.CMSounds;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Custom Music — play your own tracks as vanilla jukebox discs.
 *
 * Design in one paragraph: the server scans {@code config/custommusic/music/}, converts every
 * file to mono 44.1 kHz Ogg Vorbis and maps each track to one of {@link CMSounds#SLOT_COUNT}
 * pre-registered {@code SoundEvent} slots. A virtual (in-memory) SERVER_DATA pack injects
 * {@code data/custommusic/jukebox_song/<id>.json} entries plus the {@code minecraft:music_discs}
 * item tag, a virtual CLIENT_RESOURCES pack generates {@code sounds.json} for the slots and
 * serves the synced audio files. Playback therefore runs 100% through vanilla
 * {@code SoundSource.RECORDS} positioning — no sound engine surgery needed.
 */
@Mod(CustomMusicMod.MODID)
public final class CustomMusicMod {
    public static final String MODID = "custommusic";

    public CustomMusicMod(IEventBus modBus, ModContainer container) {
        CMConfig.register(container);
        CMItems.ITEMS.register(modBus);
        CMSounds.SOUNDS.register(modBus);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
