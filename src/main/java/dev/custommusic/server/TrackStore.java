package dev.custommusic.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import dev.custommusic.CustomMusicMod;
import dev.custommusic.common.CMConfig;
import dev.custommusic.common.CMItems;
import dev.custommusic.common.CMSounds;
import dev.custommusic.common.NameSanitizer;
import dev.custommusic.common.TrackEntry;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.EitherHolder;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.JukeboxSong;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Server-side track store: scans {@code config/custommusic/music/}, converts files (with a
 * content-hash cache so nothing is re-encoded twice), assigns deterministic sound slots and
 * persists the manifest to {@code config/custommusic/converted/manifest.json}.
 */
public final class TrackStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final TrackStore INSTANCE = new TrackStore();

    public static TrackStore get() {
        return INSTANCE;
    }

    public static final Path MUSIC_DIR = FMLPaths.CONFIGDIR.get().resolve(CustomMusicMod.MODID + "/music");
    public static final Path CONVERTED_DIR = FMLPaths.CONFIGDIR.get().resolve(CustomMusicMod.MODID + "/converted");

    private final Map<String, TrackEntry> bySlug = new TreeMap<>();
    private final Map<String, TrackEntry> previousManifest = new HashMap<>();
    private boolean scanned;

    private TrackStore() {
    }

    public synchronized void ensureScanned() {
        if (!scanned) rescan();
    }

    public synchronized int forceRescan() {
        rescan();
        return bySlug.size();
    }

    public synchronized Collection<TrackEntry> entries() {
        return List.copyOf(bySlug.values());
    }

    public synchronized TrackEntry bySlug(String slug) {
        return bySlug.get(slug);
    }

    private void rescan() {
        previousManifest.clear();
        previousManifest.putAll(bySlug);
        bySlug.clear();
        try {
            Files.createDirectories(MUSIC_DIR);
            Files.createDirectories(CONVERTED_DIR);
        } catch (IOException e) {
            LOGGER.error("[CustomMusic] Cannot create the music folders", e);
            scanned = true;
            return;
        }

        List<Path> files;
        try (var stream = Files.list(MUSIC_DIR)) {
            // Alphabetical order keeps slug dedup + slot assignment deterministic.
            files = stream.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                .toList();
        } catch (IOException e) {
            LOGGER.error("[CustomMusic] Cannot list {}", MUSIC_DIR, e);
            scanned = true;
            return;
        }

        int maxTracks = Math.min(CMConfig.MAX_TRACKS.get(), CMSounds.SLOT_COUNT);
        Set<String> usedSlugs = new HashSet<>();
        boolean[] slotUsed = new boolean[CMSounds.SLOT_COUNT];
        int converted = 0, reused = 0, skipped = 0;

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String ext = NameSanitizer.extension(fileName);
            if (!ext.equals("ogg") && !ext.equals("mp3") && !ext.equals("wav")) continue;
            if (bySlug.size() >= maxTracks) {
                LOGGER.warn("[CustomMusic] Skipping '{}': track limit reached ({})", fileName, maxTracks);
                skipped++;
                continue;
            }
            try {
                long size = Files.size(file);
                if (size > CMConfig.MAX_FILE_SIZE_MB.get() * 1024L * 1024L) {
                    LOGGER.warn("[CustomMusic] Skipping '{}': larger than {} MB", fileName,
                        CMConfig.MAX_FILE_SIZE_MB.get());
                    skipped++;
                    continue;
                }
                String slug = NameSanitizer.dedupe(NameSanitizer.slugOf(fileName), usedSlugs);
                String title = NameSanitizer.titleOf(fileName);
                String shaSrc = AudioConverter.sha256(file);
                Path targetOgg = CONVERTED_DIR.resolve(slug + ".ogg");

                AudioConverter.Result result;
                TrackEntry cached = previousManifest.get(slug);
                if (cached != null && cached.sha256Source().equals(shaSrc)
                    && Files.isRegularFile(targetOgg)) {
                    result = new AudioConverter.Result(targetOgg, cached.durationSeconds(),
                        cached.sha256Ogg(), true);
                    reused++;
                } else {
                    result = AudioConverter.convertToMonoOgg(file, targetOgg, ext);
                    if (result == null) {
                        skipped++;
                        continue;
                    }
                    converted++;
                }

                if (result.durationSeconds() > CMConfig.MAX_DURATION_SECONDS.get()) {
                    LOGGER.warn("[CustomMusic] Skipping '{}': longer than {} s", fileName,
                        CMConfig.MAX_DURATION_SECONDS.get());
                    if (!result.passthrough()) {
                        try {
                            Files.deleteIfExists(targetOgg);
                        } catch (IOException ignored) {
                        }
                    }
                    skipped++;
                    continue;
                }

                int slot = pickSlot(slug, slotUsed);
                if (slot < 0) {
                    LOGGER.warn("[CustomMusic] Skipping '{}': no free sound slot (max {} tracks)",
                        fileName, CMSounds.SLOT_COUNT);
                    skipped++;
                    continue;
                }
                bySlug.put(slug, new TrackEntry(slug, title, fileName, result.durationSeconds(),
                    size, shaSrc, result.sha256Ogg(), slot));
            } catch (Exception e) {
                // A broken file must never crash the server: warn + skip.
                LOGGER.warn("[CustomMusic] Skipping '{}': {}", fileName, e.toString(), e);
                skipped++;
            }
        }

        writeManifest();
        scanned = true;
        LOGGER.info("[CustomMusic] Scan complete: {} track(s) — {} converted, {} reused from cache, {} skipped",
            bySlug.size(), converted, reused, skipped);
    }

    /** Deterministic slot assignment (stable hash + linear probing over free slots). */
    private int pickSlot(String slug, boolean[] used) {
        int start = Math.floorMod(slug.hashCode(), used.length);
        for (int i = 0; i < used.length; i++) {
            int s = (start + i) % used.length;
            if (!used[s]) {
                used[s] = true;
                return s;
            }
        }
        return -1;
    }

    private void writeManifest() {
        try {
            Files.writeString(CONVERTED_DIR.resolve("manifest.json"), GSON.toJson(bySlug.values()));
        } catch (IOException e) {
            LOGGER.error("[CustomMusic] Failed to write the manifest", e);
        }
    }

    /** The disc item: vanilla music_disc behaviour via the jukebox_playable component. */
    public ItemStack makeDisc(TrackEntry entry, MinecraftServer server) {
        ItemStack stack = new ItemStack(CMItems.MUSIC_DISC.get());
        ResourceKey<JukeboxSong> songKey =
            ResourceKey.create(Registries.JUKEBOX_SONG, CustomMusicMod.id(entry.slug()));
        Holder<JukeboxSong> song = server.registryAccess()
            .lookupOrThrow(Registries.JUKEBOX_SONG)
            .getOrThrow(songKey);
        stack.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new EitherHolder<>(song), true));
        return stack;
    }

    /** JSON for data/custommusic/jukebox_song/<slug>.json (fields as in vanilla 1.21). */
    public String jukeboxSongJson(TrackEntry entry) {
        String safeTitle = GSON.toJson(entry.title()); // quoted + escaped JSON string
        return """
            {
              "sound_event": "%s",
              "description": {"text": %s},
              "length_in_seconds": %s,
              "comparator_output": 15
            }""".formatted(CMSounds.slotId(entry.slot()), safeTitle,
            String.format(Locale.ROOT, "%.2f", entry.durationSeconds()));
    }
}
