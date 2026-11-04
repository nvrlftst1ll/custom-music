package dev.custommusic.server;

import dev.custommusic.CustomMusicMod;
import dev.custommusic.common.TrackEntry;
import dev.custommusic.common.VirtualPackResources;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Injects an in-memory SERVER_DATA pack with one {@code jukebox_song} JSON per track plus the
 * {@code minecraft:music_discs} item tag. This is what turns scanned tracks into real
 * data-driven registry entries — no {@code RegistryDataLoader} mixin needed.
 *
 * The pack fires on every datapack (re)load — including vanilla /reload — and its contents
 * are rebuilt lazily from the current {@link TrackStore} scan each time the pack is opened.
 */
@EventBusSubscriber(modid = CustomMusicMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ServerPackInserter {
    public static final String PACK_ID = "custommusic_data";
    private static final int DATA_PACK_FORMAT = 48; // 1.21.1

    private ServerPackInserter() {
    }

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) return;
        TrackStore.get().ensureScanned();
        Component title = Component.translatable("pack.custommusic.data");
        Pack.ResourcesSupplier resources = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo location) {
                return new VirtualPackResources(PACK_ID, title, DATA_PACK_FORMAT,
                    Set.of(CustomMusicMod.MODID, "minecraft"), buildFiles());
            }

            @Override
            public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                return this.openPrimary(location);
            }
        };
        Pack pack = Pack.readMetaAndCreate(
            new PackLocationInfo(PACK_ID, title, PackSource.BUILT_IN, Optional.empty()),
            resources,
            PackType.SERVER_DATA,
            new PackSelectionConfig(true, Pack.Position.BOTTOM, true));
        event.addRepositorySource(packs -> packs.accept(pack));
    }

    /** Called fresh every time the pack is opened (i.e. on every datapack load). */
    static Map<String, Supplier<InputStream>> buildFiles() {
        Map<String, Supplier<InputStream>> files = new LinkedHashMap<>();
        String tag = """
            {
              "replace": false,
              "values": ["custommusic:music_disc"]
            }""";
        files.put("data/minecraft/tags/item/music_discs.json", stream(tag));
        for (TrackEntry entry : TrackStore.get().entries()) {
            String json = TrackStore.get().jukeboxSongJson(entry);
            files.put("data/" + CustomMusicMod.MODID + "/jukebox_song/" + entry.slug() + ".json",
                stream(json));
        }
        return files;
    }

    private static Supplier<InputStream> stream(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return () -> new ByteArrayInputStream(bytes);
    }
}
