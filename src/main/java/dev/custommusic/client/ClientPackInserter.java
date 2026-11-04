package dev.custommusic.client;

import dev.custommusic.CustomMusicMod;
import dev.custommusic.common.VirtualPackResources;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.util.Optional;
import java.util.Set;

/**
 * In-memory CLIENT_RESOURCES pack: a generated sounds.json ({@code custommusic:slot_<n>} ->
 * cached track file, streamed) plus the cached .ogg files themselves. The vanilla
 * SoundManager resolves everything from there — this is why the mod needs no sound engine
 * mixins at all. Contents are re-read on every resource reload (F3+T included).
 */
@EventBusSubscriber(modid = CustomMusicMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientPackInserter {
    public static final String PACK_ID = "custommusic_sounds";
    private static final int RESOURCE_PACK_FORMAT = 34; // 1.21.1

    private ClientPackInserter() {
    }

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) return;
        Component title = Component.translatable("pack.custommusic.sounds");
        Pack.ResourcesSupplier resources = new Pack.ResourcesSupplier() {
            @Override
            public VirtualPackResources openPrimary(PackLocationInfo location) {
                return new VirtualPackResources(PACK_ID, title, RESOURCE_PACK_FORMAT,
                    Set.of(CustomMusicMod.MODID), ClientTrackCache.buildSoundPackFiles());
            }

            @Override
            public VirtualPackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                return this.openPrimary(location);
            }
        };
        Pack pack = Pack.readMetaAndCreate(
            new PackLocationInfo(PACK_ID, title, PackSource.BUILT_IN, Optional.empty()),
            resources,
            PackType.CLIENT_RESOURCES,
            new PackSelectionConfig(true, Pack.Position.BOTTOM, true));
        event.addRepositorySource(packs -> packs.accept(pack));
    }
}
