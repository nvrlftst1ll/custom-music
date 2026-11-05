package dev.custommusic.server;

import com.mojang.logging.LogUtils;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;
import net.sourceforge.jaad.adts.ADTSDemultiplexer;
import net.sourceforge.jaad.mp4.MP4Container;
import net.sourceforge.jaad.mp4.api.AudioTrack;
import net.sourceforge.jaad.mp4.api.Frame;
import net.sourceforge.jaad.mp4.api.Movie;
import net.sourceforge.jaad.mp4.api.Track;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Direct AAC decoding via the bundled JAAD library (public domain).
 *
 * <p>This deliberately bypasses JAAD's JavaSound SPI: the SPI can only consume a plain
 * {@link InputStream}, so it cannot seek — and MP4 files whose {@code moov} box sits at the
 * end of the file (i.e. everything not "faststart"-optimized, like many recorder/voice-memo
 * files) fail with {@code "movie box at end of file, need random access"}. Here the file is
 * opened as a {@link RandomAccessFile}, which handles moov-anywhere. Raw ADTS {@code .aac}
 * streams are decoded through {@link ADTSDemultiplexer}.</p>
 *
 * <p>Output is 16-bit little-endian PCM at the source sample rate/channel count — the caller
 * ({@link AudioConverter}) downmixes and resamples it to mono 44.1 kHz.</p>
 */
public final class JaadAudio {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 16-bit little-endian PCM plus its format. */
    public record Pcm(byte[] pcm, float sampleRate, int channels) {
    }

    private JaadAudio() {
    }

    public static boolean handles(String ext) {
        return "m4a".equals(ext) || "mp4".equals(ext) || "aac".equals(ext);
    }

    /** @throws IOException with a human-readable reason when the file cannot be decoded. */
    public static Pcm decode(Path file, String ext, int maxPcmBytes) throws IOException {
        if ("aac".equals(ext)) {
            return decodeAdts(file, maxPcmBytes);
        }
        return decodeMp4(file, maxPcmBytes);
    }

    private static Pcm decodeMp4(Path file, int maxPcmBytes) throws IOException {
        // MP4InputStream(RandomAccessFile) is package-private, but MP4Container(RandomAccessFile)
        // is public — and frame data is read straight from the RandomAccessFile by offset.
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            Movie movie = new MP4Container(raf).getMovie();
            AudioTrack track = null;
            for (Track t : movie.getTracks()) {
                if (t instanceof AudioTrack audio) {
                    track = audio;
                    break;
                }
            }
            if (track == null) throw new IOException("no audio track found in the file");

            Decoder decoder = new Decoder(track.getDecoderSpecificInfo());
            SampleBuffer buffer = new SampleBuffer();
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            Frame frame;
            while ((frame = track.readNextFrame()) != null) {
                raf.seek(frame.getOffset());
                byte[] data = new byte[(int) frame.getSize()];
                raf.readFully(data);
                decoder.decodeFrame(data, buffer);
                appendPcm(out, buffer, maxPcmBytes);
            }
            return finish(out, buffer);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("AAC decode failed: " + e, e);
        }
    }

    private static Pcm decodeAdts(Path file, int maxPcmBytes) throws IOException {
        try (InputStream fin = Files.newInputStream(file)) {
            ADTSDemultiplexer adts = new ADTSDemultiplexer(fin);
            Decoder decoder = new Decoder(adts.getDecoderSpecificInfo());
            SampleBuffer buffer = new SampleBuffer();
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] frame;
            while ((frame = adts.readNextFrame()) != null) {
                decoder.decodeFrame(frame, buffer);
                appendPcm(out, buffer, maxPcmBytes);
            }
            return finish(out, buffer);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("AAC decode failed: " + e, e);
        }
    }

    private static void appendPcm(ByteArrayOutputStream out, SampleBuffer buffer, int maxPcmBytes)
        throws IOException {
        byte[] data = buffer.getData();
        if (data.length == 0) return;
        if (buffer.isBigEndian()) {
            // JAAD's SampleBuffer is big-endian on some profiles — the pipeline is LE.
            for (int i = 0; i + 1 < data.length; i += 2) {
                out.write(data[i + 1]);
                out.write(data[i]);
            }
        } else {
            out.write(data, 0, data.length);
        }
        if (out.size() > maxPcmBytes) {
            throw new IOException("decoded PCM exceeds " + (maxPcmBytes / 1024 / 1024) + " MB");
        }
    }

    private static Pcm finish(ByteArrayOutputStream out, SampleBuffer buffer) throws IOException {
        if (out.size() == 0) throw new IOException("no AAC frames decoded");
        LOGGER.debug("[CustomMusic] JAAD decoded {} PCM bytes, {} ch / {} Hz",
            out.size(), buffer.getChannels(), buffer.getSampleRate());
        return new Pcm(out.toByteArray(), buffer.getSampleRate(),
            Math.max(1, buffer.getChannels()));
    }
}
