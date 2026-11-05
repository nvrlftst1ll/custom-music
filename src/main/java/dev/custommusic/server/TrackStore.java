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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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
 *
 * <p>Reads ({@link #entries()}/{@link #bySlug(String)}) never block the server thread: the
 * scan builds an immutable snapshot and swaps it in atomically, so a background scan (see
 * {@code /customdisc reload}) can run while the game keeps using the previous track set.</p>
 */
public final class TrackStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final TrackStore INSTANCE = new TrackStore();

    /**
     * Supported input extensions. {@code .m4a/.aac/.mp4} (audio-only) are decoded by the
     * bundled JAAD codec (pure Java, registered as a JavaSound SPI); {@code .ogg/.mp3/.wav}
     * use the JavaZoom codecs. Everything is transcoded to mono 44.1 kHz Ogg Vorbis.
     */
    public static final Set<String> SUPPORTED_EXTENSIONS =
        Set.of("ogg", "mp3", "wav", "m4a", "aac", "mp4");

    public static TrackStore get() {
        return INSTANCE;
    }

    public static final Path MUSIC_DIR = FMLPaths.CONFIGDIR.get().resolve(CustomMusicMod.MODID + "/music");
    public static final Path CONVERTED_DIR = FMLPaths.CONFIGDIR.get().resolve(CustomMusicMod.MODID + "/converted");

    /**
     * The classic wrong-folder trap: launchers (TLauncher & co.) can run a version from an
     * isolated {@code .minecraft/versions/<name>/} game dir, while the user drops files into
     * the shared {@code .minecraft/config/custommusic/music/} — or vice versa. If the primary
     * folder has no supported files but this sibling folder (config of the game dir's parent)
     * has some, it is scanned instead and loudly logged.
     */
    private static final Path ALT_MUSIC_DIR = altMusicDir();

    private static Path altMusicDir() {
        Path parent = FMLPaths.GAMEDIR.get().getParent();
        return parent == null ? null
            : parent.resolve("config").resolve(CustomMusicMod.MODID).resolve("music").normalize();
    }

    /** One scan pass outcome — used for chat feedback and logs. */
    public record ScanReport(Path folder, int tracks, int converted, int reused, int skipped,
                             int unsupportedFiles, boolean changed, List<String> problems) {
    }

    /** Immutable snapshot of the last successful scan; {@link Map#of()} before the first one. */
    private volatile Map<String, TrackEntry> bySlug = Map.of();
    private volatile ScanReport lastReport =
        new ScanReport(MUSIC_DIR, 0, 0, 0, 0, 0, false, List.of());
    private boolean scanned;

    private TrackStore() {
    }

    /** Called from the virtual datapack registration (startup / datapack reload). */
    public synchronized void ensureScanned() {
        if (!scanned) forceRescan();
    }

    /** Rescans the music folder (blocking — call from a background thread when user-facing). */
    public synchronized ScanReport forceRescan() {
        Map<String, TrackEntry> previous = bySlug;
        ScanReport report = rescan(previous);
        lastReport = report;
        return report;
    }

    public ScanReport lastReport() {
        return lastReport;
    }

    /** Folder that the last scan actually used (primary or the launcher shared-root fallback). */
    public Path musicFolder() {
        Path folder = lastReport.folder();
        return folder != null ? folder : MUSIC_DIR;
    }

    public Collection<TrackEntry> entries() {
        return bySlug.values();
    }

    public TrackEntry bySlug(String slug) {
        return bySlug.get(slug);
    }

    private ScanReport rescan(Map<String, TrackEntry> previous) {
        List<Path> files = listMusicFiles(MUSIC_DIR);
        Path folder = MUSIC_DIR;
        if (!hasSupportedFile(files) && ALT_MUSIC_DIR != null && !ALT_MUSIC_DIR.equals(MUSIC_DIR)
            && Files.isDirectory(ALT_MUSIC_DIR)) {
            List<Path> altFiles = listMusicFiles(ALT_MUSIC_DIR);
            if (hasSupportedFile(altFiles)) {
                folder = ALT_MUSIC_DIR;
                files = altFiles;
                LOGGER.warn("[CustomMusic] The primary music folder {} has no audio files — "
                        + "scanning the shared game-root folder {} instead (isolated version game dir?)",
                    MUSIC_DIR, ALT_MUSIC_DIR);
            }
        }
        LOGGER.info("[CustomMusic] Scanning music folder: {} ({} file(s))", folder.toAbsolutePath(), files.size());

        int maxTracks = Math.min(CMConfig.MAX_TRACKS.get(), CMSounds.SLOT_COUNT);
        Set<String> usedSlugs = new HashSet<>();
        boolean[] slotUsed = new boolean[CMSounds.SLOT_COUNT];
        Map<String, TrackEntry> next = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        int converted = 0, reused = 0, skipped = 0, unsupported = 0;

        try {
            Files.createDirectories(MUSIC_DIR);
            Files.createDirectories(CONVERTED_DIR);
        } catch (IOException e) {
            LOGGER.error("[CustomMusic] Cannot create the music folders", e);
            scanned = true;
            return new ScanReport(folder, 0, 0, 0, 0, 0, false,
                List.of("cannot create folders: " + e));
        }

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String ext = NameSanitizer.extension(fileName);
            if (!SUPPORTED_EXTENSIONS.contains(ext)) {
                // Report (don't just drop) unknown formats: the #1 "mod doesn't see my tracks" cause.
                unsupported++;
                LOGGER.warn("[CustomMusic] Ignoring '{}': unsupported format .{} (supported: {})",
                    fileName, ext, SUPPORTED_EXTENSIONS);
                continue;
            }
            if (next.size() >= maxTracks) {
                problems.add(fileName + " — track limit reached (" + maxTracks + ")");
                LOGGER.warn("[CustomMusic] Skipping '{}': track limit reached ({})", fileName, maxTracks);
                skipped++;
                continue;
            }
            try {
                long size = Files.size(file);
                if (size > CMConfig.MAX_FILE_SIZE_MB.get() * 1024L * 1024L) {
                    problems.add(fileName + " — larger than " + CMConfig.MAX_FILE_SIZE_MB.get() + " MB");
                    skipped++;
                    continue;
                }
                String slug = NameSanitizer.dedupe(NameSanitizer.slugOf(fileName), usedSlugs);
                String title = NameSanitizer.titleOf(fileName);
                String shaSrc = AudioConverter.sha256(file);
                Path targetOgg = CONVERTED_DIR.resolve(slug + ".ogg");

                AudioConverter.Result result;
                TrackEntry cached = previous.get(slug);
                if (cached != null && cached.sha256Source().equals(shaSrc)
                    && Files.isRegularFile(targetOgg)) {
                    result = new AudioConverter.Result(targetOgg, cached.durationSeconds(),
                        cached.sha256Ogg(), true);
                    reused++;
                } else {
                    result = AudioConverter.convertToMonoOgg(file, targetOgg, ext);
                    converted++;
                }

                if (result.durationSeconds() > CMConfig.MAX_DURATION_SECONDS.get()) {
                    problems.add(fileName + " — longer than " + CMConfig.MAX_DURATION_SECONDS.get() + " s");
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
                    problems.add(fileName + " — no free sound slot (max " + CMSounds.SLOT_COUNT + " tracks)");
                    skipped++;
                    continue;
                }
                next.put(slug, new TrackEntry(slug, title, fileName, result.durationSeconds(),
                    size, shaSrc, result.sha256Ogg(), slot));
            } catch (Exception e) {
                // A broken file must never crash the server: warn + skip.
                problems.add(fileName + " — " + (e.getMessage() != null ? e.getMessage() : e.toString()));
                LOGGER.warn("[CustomMusic] Skipping '{}': {}", fileName, e.toString(), e);
                skipped++;
            }
        }

        boolean changed = !signature(next).equals(signature(previous));
        Map<String, TrackEntry> snapshot = Collections.unmodifiableMap(next);
        bySlug = snapshot; // atomic swap: readers never see a half-scanned state
        writeManifest(snapshot);
        scanned = true;
        LOGGER.info("[CustomMusic] Scan complete: {} track(s) — {} converted, {} reused from cache, "
                + "{} skipped, {} unsupported (folder: {})",
            next.size(), converted, reused, skipped, unsupported, folder);
        return new ScanReport(folder, next.size(), converted, reused, skipped, unsupported,
            changed, List.copyOf(problems));
    }

    private static List<Path> listMusicFiles(Path dir) {
        try (var stream = Files.list(dir)) {
            // Alphabetical order keeps slug dedup + slot assignment deterministic.
            return stream.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean hasSupportedFile(List<Path> files) {
        for (Path file : files) {
            if (SUPPORTED_EXTENSIONS.contains(NameSanitizer.extension(file.getFileName().toString()))) {
                return true;
            }
        }
        return false;
    }

    /** Stable content signature: any track added/removed/changed → different string. */
    private static String signature(Map<String, TrackEntry> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, TrackEntry> e : new TreeMap<>(map).entrySet()) {
            sb.append(e.getKey()).append('|')
                .append(e.getValue().slot()).append('|')
                .append(Math.round(e.getValue().durationSeconds() * 10)).append('|')
                .append(e.getValue().sha256Ogg()).append(';');
        }
        return sb.toString();
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

    private void writeManifest(Map<String, TrackEntry> tracks) {
        try {
            Files.writeString(CONVERTED_DIR.resolve("manifest.json"), GSON.toJson(tracks.values()));
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
