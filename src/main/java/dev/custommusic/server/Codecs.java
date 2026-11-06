package dev.custommusic.server;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Availability probes for the four bundled codec stacks (JAAD/AAC, mp3spi, vorbisspi,
 * jVorbisEnc). NeoForge loads the jar-in-jar libraries ({@code audio-codecs} +
 * {@code jvorbisenc-patched}) into the game module layer at startup. When that silently
 * fails — an incomplete mod jar, a repack that stripped {@code META-INF/jarjar}, or a
 * launcher that ignored it — every conversion path dies with a bare
 * {@link NoClassDefFoundError}. That is an <em>Error</em>: it escapes the per-file
 * {@code catch (Exception)} guards and kills the whole folder scan with an opaque
 * "scan failed" message instead of skipping one file (the reload-crash bug of 1.1.0).
 *
 * <p>These probes turn that scenario into a per-file, human-readable skip reason plus one
 * loud diagnostics block in the log explaining how to fix it.</p>
 */
public final class Codecs {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String JAAD = "net.sourceforge.jaad.mp4.MP4Container";
    private static final String MP3 = "javazoom.spi.mpeg.sampled.file.MpegAudioFileReader";
    private static final String VORBIS = "javazoom.spi.vorbis.sampled.file.VorbisAudioFileReader";
    private static final String ENCODER = "biniu.vorbis.VorbisEnc";

    /** Log the missing-codec block at most once per JVM. */
    private static volatile boolean reported;

    private Codecs() {
    }

    /** Called at the start of every folder scan; only the first call can log. */
    public static void reportOnce() {
        if (reported) return;
        synchronized (Codecs.class) {
            if (reported) return;
            boolean jaad = present(JAAD);
            boolean mp3 = present(MP3);
            boolean vorbis = present(VORBIS);
            boolean encoder = present(ENCODER);
            if (jaad && mp3 && vorbis && encoder) {
                reported = true; // all bundled codecs visible — stay silent
                return;
            }
            LOGGER.warn("[CustomMusic] Some bundled audio codecs are NOT available in this instance:");
            if (!mp3) LOGGER.warn("[CustomMusic]   - MP3 decoder missing (.mp3 input will fail)");
            if (!vorbis) LOGGER.warn("[CustomMusic]   - Vorbis decoder missing (.ogg re-encode will fail)");
            if (!jaad) LOGGER.warn("[CustomMusic]   - AAC decoder missing (.m4a/.aac/.mp4 input will fail)");
            if (!encoder) LOGGER.warn("[CustomMusic]   - Ogg Vorbis encoder missing (all conversion will fail)");
            LOGGER.warn("[CustomMusic] The jar-in-jar libraries audio-codecs/jvorbisenc-patched were not "
                + "loaded. Most likely the mod jar is incomplete or was repacked without "
                + "META-INF/jarjar. Re-download the full jar and keep only ONE version of Custom "
                + "Music in the mods folder.");
            reported = true;
        }
    }

    /** @return true when the bundled JAAD (AAC/MP4) classes can be linked. */
    public static boolean jaadPresent() {
        return present(JAAD);
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, Codecs.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
