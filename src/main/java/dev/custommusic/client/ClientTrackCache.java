package dev.custommusic.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import dev.custommusic.CustomMusicMod;
import dev.custommusic.common.CMSounds;
import dev.custommusic.common.TrackEntry;
import dev.custommusic.common.payload.CMPayloads;
import dev.custommusic.common.payload.CMPayloads.RequestTracksPayload;
import dev.custommusic.common.payload.CMPayloads.TrackChunkPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Client-side cache of tracks synced from the server (config/custommusic/client-cache/),
 * plus the builder that turns it into the virtual sound pack (sounds.json + audio files).
 */
public final class ClientTrackCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final int CHUNK_SIZE = CMPayloads.CHUNK_SIZE;

    public static final Path CACHE_DIR = FMLPaths.CONFIGDIR.get().resolve(CustomMusicMod.MODID + "/client-cache");
    private static final Path PENDING_DIR = CACHE_DIR.resolve("pending");

    private static volatile List<TrackEntry> manifest = readManifest();
    private static final Set<String> pendingSlugs = new HashSet<>();
    private static long lastWarnMillis;

    private ClientTrackCache() {
    }

    /** Server pushed a fresh track list: diff against the cache and request what is missing. */
    public static synchronized void applyServerList(List<TrackEntry> serverTracks) {
        Map<String, TrackEntry> previous = new LinkedHashMap<>();
        for (TrackEntry t : manifest) previous.put(t.slug(), t);

        List<String> toDownload = new ArrayList<>();
        for (TrackEntry t : serverTracks) {
            TrackEntry old = previous.get(t.slug());
            boolean ok = old != null && old.sha256Ogg() != null
                && old.sha256Ogg().equals(t.sha256Ogg())
                && Files.isRegularFile(CACHE_DIR.resolve(t.slug() + ".ogg"));
            if (!ok) toDownload.add(t.slug());
        }
        boolean changed = toDownload.isEmpty() && !manifest.equals(serverTracks);
        manifest = List.copyOf(serverTracks);

        if (!toDownload.isEmpty()) {
            pendingSlugs.addAll(toDownload);
            LOGGER.info("[CustomMusic] Requesting {} track(s) from the server", toDownload.size());
            PacketDistributor.sendToServer(new RequestTracksPayload(toDownload));
        } else if (changed) {
            saveManifest();
            ClientReloadUtil.reloadResources();
        }
    }

    /** One audio chunk arrived (offsets are absolute, order does not matter). */
    public static synchronized void handleChunk(TrackChunkPayload payload) {
        String slug = payload.slug();
        if (!pendingSlugs.contains(slug)) return;
        try {
            Files.createDirectories(PENDING_DIR);
            Path part = PENDING_DIR.resolve(slug + ".part");
            try (RandomAccessFile raf = new RandomAccessFile(part.toFile(), "rw")) {
                raf.seek((long) payload.index() * CHUNK_SIZE);
                raf.write(payload.data());
            }
            if (payload.index() + 1 >= payload.total()) {
                TrackEntry entry = find(manifest, slug);
                String sha = sha256(part);
                if (entry != null && sha != null && sha.equals(entry.sha256Ogg())) {
                    Files.move(part, CACHE_DIR.resolve(slug + ".ogg"), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    LOGGER.warn("[CustomMusic] SHA-256 mismatch for '{}', discarding", slug);
                    Files.deleteIfExists(part);
                }
                pendingSlugs.remove(slug);
                if (pendingSlugs.isEmpty()) {
                    saveManifest();
                    LOGGER.info("[CustomMusic] All tracks synced — reloading sound resources");
                    ClientReloadUtil.reloadResources();
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[CustomMusic] Failed to store chunk for '{}': {}", slug, e.toString());
            pendingSlugs.remove(slug); // abort this transfer; rejoin or /customdisc reload retries
        }
    }

    /** Tracks that are fully present locally (used by the pack builder and the chooser). */
    public static synchronized List<TrackEntry> available() {
        List<TrackEntry> result = new ArrayList<>();
        for (TrackEntry t : manifest) {
            if (Files.isRegularFile(CACHE_DIR.resolve(t.slug() + ".ogg"))) result.add(t);
        }
        return result;
    }

    public static synchronized boolean hasSlot(int slot) {
        for (TrackEntry t : manifest) {
            if (t.slot() == slot) return true;
        }
        return false;
    }

    /** Called from SoundEngineMixin: friendly hint instead of a cryptic vanilla warning. */
    public static void noteUnknownSlot(int slot, ResourceLocation location) {
        if (System.currentTimeMillis() - lastWarnMillis < 30_000) return;
        lastWarnMillis = System.currentTimeMillis();
        LOGGER.warn("[CustomMusic] '{}' is not synced to this client yet — it is either still "
            + "downloading from the server, or the server needs a /customdisc reload.", location);
    }

    /** Files for the virtual CLIENT_RESOURCES pack (fresh on every resource reload). */
    public static Map<String, Supplier<InputStream>> buildSoundPackFiles() {
        Map<String, Supplier<InputStream>> files = new LinkedHashMap<>();
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (TrackEntry t : available()) {
            if (!first) json.append(',');
            first = false;
            // NB: keys in sounds.json must be PLAIN names ("slot_7"), never
            // "custommusic:slot_7": vanilla reads each pack's sounds.json and builds
            // the event id as fromNamespaceAndPath(<pack namespace>, key) — a
            // namespaced key would register "custommusic:custommusic:slot_7" (or
            // fail validation), and the SoundEvent would never resolve. The
            // namespace comes from the pack itself (assets/custommusic/...).
            json.append("\n  \"").append(CMSounds.slotName(t.slot()))
                .append("\": {\"sounds\": [{\"name\": \"")
                .append(CustomMusicMod.MODID).append(":tracks/").append(t.slug())
                .append("\", \"stream\": true}]}");
            files.put("assets/" + CustomMusicMod.MODID + "/sounds/tracks/" + t.slug() + ".ogg",
                fileSupplier(CACHE_DIR.resolve(t.slug() + ".ogg")));
        }
        json.append("\n}");
        String soundsJson = json.toString();
        files.put("assets/" + CustomMusicMod.MODID + "/sounds.json",
            () -> new java.io.ByteArrayInputStream(soundsJson.getBytes(StandardCharsets.UTF_8)));
        return files;
    }

    private static Supplier<InputStream> fileSupplier(Path path) {
        return () -> {
            try {
                return Files.newInputStream(path);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
    }

    private static TrackEntry find(List<TrackEntry> list, String slug) {
        for (TrackEntry t : list) {
            if (t.slug().equals(slug)) return t;
        }
        return null;
    }

    private static List<TrackEntry> readManifest() {
        try {
            Path f = CACHE_DIR.resolve("manifest.json");
            if (Files.isRegularFile(f)) {
                List<TrackEntry> list = GSON.fromJson(Files.readString(f),
                    new TypeToken<List<TrackEntry>>() {
                    }.getType());
                return list == null ? List.of() : list;
            }
        } catch (Exception e) {
            LOGGER.warn("[CustomMusic] Cannot read the client manifest: {}", e.toString());
        }
        return List.of();
    }

    private static void saveManifest() {
        try {
            Files.createDirectories(CACHE_DIR);
            Files.writeString(CACHE_DIR.resolve("manifest.json"), GSON.toJson(manifest));
        } catch (IOException e) {
            LOGGER.warn("[CustomMusic] Cannot save the client manifest: {}", e.toString());
        }
    }

    private static String sha256(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }
}
