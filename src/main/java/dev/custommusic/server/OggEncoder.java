package dev.custommusic.server;

import biniu.ogg.Page;
import biniu.ogg.Packet;
import biniu.ogg.StreamState;
import biniu.vorbis.Block;
import biniu.vorbis.Comment;
import biniu.vorbis.DspState;
import biniu.vorbis.Info;
import biniu.vorbis.VorbisEnc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Mono 44.1 kHz Ogg Vorbis encoder built on the bundled jVorbisEnc (a pure-Java port of
 * libvorbis, no natives) — follows the canonical {@code encoder_example.c} flow:
 * VBR headers → analysis loop → bitrate-managed packets → Ogg pages.
 *
 * <p>This replaces the original plan of encoding via the vorbisspi JavaSound writer — that
 * writer does not exist (vorbisspi only registers an {@code AudioFileReader} and a
 * {@code FormatConversionProvider} for DEcoding), which made every non-passthrough
 * conversion silently depend on the ffmpeg fallback.</p>
 */
public final class OggEncoder {
    /** Blocks of PCM frames fed into the analysis pipeline at a time. */
    private static final int CHUNK_FRAMES = 1024;
    /** VBR quality: ~0.4 ≈ q4 ≈ ~100 kbps mono — plenty for jukebox music. */
    private static final float QUALITY = 0.4f;

    private OggEncoder() {
    }

    /**
     * @param pcmLe 16-bit little-endian MONO 44.1 kHz PCM
     * @return a complete .ogg file (headers + audio pages)
     */
    public static byte[] encodeMono44100(byte[] pcmLe) throws IOException {
        int frames = pcmLe.length / 2;
        ByteBuffer samples = ByteBuffer.wrap(pcmLe).order(ByteOrder.LITTLE_ENDIAN);

        Info vi = new Info();
        vi.init();
        VorbisEnc encoder = new VorbisEnc();
        if (encoder.initVBR(vi, 1, 44100, QUALITY) != 0) {
            throw new IOException("jVorbisEnc initVBR failed");
        }
        Comment vc = new Comment();
        vc.init();
        vc.addTag("ENCODER", "Custom Music mod (jVorbisEnc)");
        DspState vd = new DspState();
        vd.analysisInit(vi);
        Block vb = new Block(vd);
        vb.blockInit(vd); // the ctor only stores the reference — this allocates the block state
        StreamState os = new StreamState();
        os.init((int) (System.nanoTime() & 0x7fffffff));

        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try {
            // --- headers: 3 packets, flushed out as pages ---
            Packet hMain = new Packet(), hComments = new Packet(), hBooks = new Packet();
            vd.analysisHeaderOut(vc, hMain, hComments, hBooks);
            os.packetIn(hMain);
            os.packetIn(hComments);
            os.packetIn(hBooks);
            Page og = new Page();
            while (os.flush(og)) {
                writePage(out, og);
            }

            // --- audio loop ---
            int pos = 0;
            boolean eosWritten = false;
            while (true) {
                if (pos < frames) {
                    int n = Math.min(CHUNK_FRAMES, frames - pos);
                    float[][] buffer = vd.analysisBuffer(n);
                    for (int i = 0; i < n; i++) {
                        buffer[0][i] = samples.getShort((pos + i) * 2) / 32768f;
                    }
                    vd.analysisWrote(n);
                    pos += n;
                } else if (!eosWritten) {
                    vd.analysisWrote(0); // end-of-stream marker
                    eosWritten = true;
                }

                while (vb.analysisBlockOut()) {
                    Packet op = new Packet();
                    vb.analysis(op);
                    vb.bitrateAddBlock();
                    Packet flushed = new Packet();
                    while (vd.bitrateFlushPacket(flushed)) {
                        os.packetIn(flushed);
                        while (os.pageOut(og)) {
                            writePage(out, og);
                        }
                    }
                }

                if (eosWritten) break;
            }

            // --- final flush: force out any pages still buffered in the stream ---
            while (os.flush(og)) {
                writePage(out, og);
            }
        } finally {
            try { os.clear(); } catch (Throwable ignored) { }
            try { vb.clear(); } catch (Throwable ignored) { }
            try { vd.clear(); } catch (Throwable ignored) { }
            try { vi.clear(); } catch (Throwable ignored) { }
        }

        if (out.size() == 0) throw new IOException("jVorbisEnc produced no output");
        return out.toByteArray();
    }

    private static void writePage(ByteArrayOutputStream out, Page og) {
        out.write(og.header_base, og.header, og.header_len);
        out.write(og.body_base, og.body, og.body_len);
    }
}
