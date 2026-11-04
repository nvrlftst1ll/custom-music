package dev.custommusic.server;

import com.mojang.logging.LogUtils;
import dev.custommusic.common.CMConfig;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * Server-side audio pipeline. Everything is converted to 16-bit mono 44.1 kHz Ogg Vorbis —
 * the only format the vanilla sound engine can stream, and mono is required for real 3D
 * distance attenuation (OpenAL ignores spatialization for stereo sources).
 *
 * Phases (see README "Этапы"):
 *  1. .ogg already mono 44.1 kHz  -> passthrough copy (no re-encode at all);
 *  2. anything else               -> decode via JavaSound SPI (vorbisspi/mp3spi, bundled),
 *                                    downmix to mono, linear resample, re-encode via
 *                                    the vorbisspi writer;
 *  3. if no codec is available    -> external ffmpeg fallback (configurable path).
 *
 * Every failure is a warning + skip: a bad file must never crash the server.
 */
public final class AudioConverter {
    public static final float TARGET_RATE = 44100.0f;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AudioFileFormat.Type OGG_TYPE = new AudioFileFormat.Type("OGG", "ogg");
    private static final int PCM_BYTES = 2;
    /** Hard cap for in-memory PCM during conversion (~10 min stereo ≈ 100 MB). */
    private static final int MAX_PCM_BYTES = 256 * 1024 * 1024;

    private AudioConverter() {
    }

    public record Result(Path oggPath, float durationSeconds, String sha256Ogg, boolean passthrough) {
    }

    /** Minimal Ogg page / Vorbis header parser: channels, sample rate, duration from granule. */
    public record OggInfo(int channels, float sampleRate, float durationSeconds) {
        public static OggInfo read(Path file) throws IOException {
            try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file), 65536))) {
                byte[] page = new byte[282];
                long maxGranule = 0;
                int channels = -1;
                float rate = -1;
                while (true) {
                    int headerRead = in.read(page, 0, 27);
                    if (headerRead < 27) break;
                    if (page[0] != 'O' || page[1] != 'g' || page[2] != 'g' || page[3] != 'S') break;
                    long granule = littleLong(page, 6);
                    int segCount = page[26] & 0xFF;
                    if (in.read(page, 27, segCount) < segCount) break;
                    int packetSize = 0;
                    for (int i = 0; i < segCount; i++) packetSize += page[27 + i] & 0xFF;
                    byte[] packet = new byte[packetSize];
                    if (!readFully(in, packet)) break;
                    if (channels < 0 && packetSize >= 30
                        && packet[0] == 1 && packet[1] == 'v' && packet[2] == 'o' && packet[3] == 'r'
                        && packet[4] == 'b' && packet[5] == 'i' && packet[6] == 's') {
                        channels = packet[11] & 0xFF;
                        rate = Float.intBitsToFloat((int) littleLong(packet, 12));
                    }
                    if (granule > maxGranule) maxGranule = granule;
                }
                if (channels > 0 && rate > 0 && maxGranule > 0) {
                    return new OggInfo(channels, rate, maxGranule / rate);
                }
                return null;
            }
        }
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /**
     * Converts {@code source} into mono 44.1 kHz Ogg Vorbis at {@code targetOgg}.
     * Returns null (and logs a warning) when no encoder is available and ffmpeg failed.
     */
    public static Result convertToMonoOgg(Path source, Path targetOgg, String ext) throws IOException {
        Files.createDirectories(targetOgg.getParent());

        // Phase 1: fast path — mono 44.1 kHz Vorbis is served as is.
        if ("ogg".equals(ext)) {
            OggInfo info = OggInfo.read(source);
            if (info != null && info.channels() == 1 && Math.abs(info.sampleRate() - TARGET_RATE) < 1.0f) {
                Files.copy(source, targetOgg, StandardCopyOption.REPLACE_EXISTING);
                return new Result(targetOgg, info.durationSeconds(), sha256(source), true);
            }
            if (info != null) {
                LOGGER.info("[CustomMusic] '{}' is {} ch / {} Hz — converting to mono 44.1 kHz Ogg",
                    source.getFileName(), info.channels(), (int) info.sampleRate());
            }
        }

        // Phase 2: decode -> downmix -> resample -> encode.
        MonoPcm mono = null;
        try {
            mono = decodeToMonoPcm(source);
        } catch (Exception e) {
            LOGGER.warn("[CustomMusic] JavaSound cannot decode '{}': {} — trying ffmpeg",
                source.getFileName(), e.toString());
        }
        boolean encoded = false;
        if (mono != null) {
            try (AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(mono.pcm()),
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, TARGET_RATE, 16, 1, PCM_BYTES, TARGET_RATE, false),
                mono.pcm().length / PCM_BYTES)) {
                AudioSystem.write(ais, OGG_TYPE, targetOgg.toFile());
                encoded = Files.isRegularFile(targetOgg) && Files.size(targetOgg) > 0;
            } catch (Exception e) {
                LOGGER.warn("[CustomMusic] Built-in Ogg encoder failed for '{}': {} — trying ffmpeg",
                    source.getFileName(), e.toString());
            }
        }

        // Phase 3: ffmpeg fallback (also used when no JavaSound codec is present at all).
        if (!encoded && !runFfmpeg(source, targetOgg)) {
            if (mono != null && !CMConfig.ALLOW_UNCONVERTIBLE_STEREO.get()) {
                LOGGER.warn("[CustomMusic] Cannot convert '{}' — no working encoder "
                    + "(see README, «MP3/WAV/стерео»). File skipped.", source.getFileName());
            }
            Files.deleteIfExists(targetOgg);
            return null;
        }

        OggInfo out = OggInfo.read(targetOgg);
        float duration = out != null ? out.durationSeconds() : (mono != null ? mono.durationSeconds() : -1);
        if (duration <= 0) {
            LOGGER.warn("[CustomMusic] Cannot determine duration of '{}' — file skipped.", source.getFileName());
            Files.deleteIfExists(targetOgg);
            return null;
        }
        return new Result(targetOgg, duration, sha256(targetOgg), false);
    }

    private record MonoPcm(byte[] pcm, float durationSeconds) {
    }

    private static MonoPcm decodeToMonoPcm(Path source) throws Exception {
        try (AudioInputStream raw = AudioSystem.getAudioInputStream(source.toFile())) {
            AudioFormat base = raw.getFormat();
            // 1) decode anything (mp3/ogg/compressed) to 16-bit PCM at the source rate
            AudioFormat pcmFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                base.getSampleRate(), 16, base.getChannels(),
                base.getChannels() * PCM_BYTES, base.getSampleRate(), false);
            AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcmFormat, raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16384];
            int n;
            while ((n = pcmStream.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_PCM_BYTES) {
                    pcmStream.close();
                    throw new IOException("decoded PCM exceeds " + (MAX_PCM_BYTES / 1024 / 1024) + " MB");
                }
            }
            pcmStream.close();
            byte[] pcm = out.toByteArray();
            int channels = Math.max(1, base.getChannels());
            // 2) downmix to mono
            byte[] monoPcm = downmix(pcm, channels);
            // 3) resample to 44.1 kHz
            byte[] resampled = resample(monoPcm, base.getSampleRate(), TARGET_RATE);
            float duration = resampled.length / (TARGET_RATE * PCM_BYTES);
            return new MonoPcm(resampled, duration);
        }
    }

    private static byte[] downmix(byte[] pcm, int channels) {
        if (channels <= 1) return pcm;
        int frames = pcm.length / (channels * PCM_BYTES);
        ByteBuffer src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer dst = ByteBuffer.allocate(frames * PCM_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int f = 0; f < frames; f++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) sum += src.getShort();
            int avg = Math.round(sum / (float) channels);
            dst.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, avg)));
        }
        return dst.array();
    }

    private static byte[] resample(byte[] mono, float srcRate, float dstRate) {
        int srcFrames = mono.length / PCM_BYTES;
        if (srcFrames == 0 || Math.abs(srcRate - dstRate) < 0.5f) return mono;
        int dstFrames = Math.round(srcFrames * dstRate / srcRate);
        ByteBuffer src = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer dst = ByteBuffer.allocate(dstFrames * PCM_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < dstFrames; i++) {
            float pos = i * srcRate / dstRate;
            int i0 = Math.min((int) pos, srcFrames - 1);
            int i1 = Math.min(i0 + 1, srcFrames - 1);
            float t = pos - i0;
            float s0 = src.getShort(i0 * PCM_BYTES);
            float s1 = src.getShort(i1 * PCM_BYTES);
            dst.putShort((short) (s0 + (s1 - s0) * t));
        }
        return dst.array();
    }

    private static boolean runFfmpeg(Path source, Path targetOgg) {
        String ffmpeg = CMConfig.FFMPEG_PATH.get();
        if (ffmpeg == null || ffmpeg.isBlank()) return false;
        try {
            Process p = new ProcessBuilder(ffmpeg, "-y", "-i", source.toString(),
                "-vn", "-ac", "1", "-ar", String.valueOf((int) TARGET_RATE),
                "-c:a", "libvorbis", "-q:a", "4", targetOgg.toString())
                .redirectErrorStream(true)
                .start();
            try (InputStream out = p.getInputStream()) {
                out.readAllBytes(); // don't deadlock on a full pipe
            }
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && Files.isRegularFile(targetOgg) && Files.size(targetOgg) > 0;
        } catch (Exception e) {
            LOGGER.warn("[CustomMusic] ffmpeg failed for '{}': {}", source.getFileName(), e.toString());
            return false;
        }
    }

    private static long littleLong(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }

    private static boolean readFully(DataInputStream in, byte[] target) throws IOException {
        int off = 0;
        while (off < target.length) {
            int n = in.read(target, off, target.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }
}
