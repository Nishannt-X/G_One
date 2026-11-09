package com.example.g_one.speech.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.example.g_one.speech.Wav;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The conversions between browser audio, Prisma's 1,024-byte frames and Timbre's output. */
class AudioUtilsTest {

    private static byte[] tone(double hz, int rate, double seconds, double amplitude) {
        int n = (int) (rate * seconds);
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            Pcm.put(pcm, i, Pcm.clamp(amplitude * 32767 * Math.sin(2 * Math.PI * hz * i / rate)));
        }
        return pcm;
    }

    private static byte[] resampleInPieces(byte[] in, int from, int to, int pieceBytes) {
        StreamResampler r = new StreamResampler(from, to);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int at = 0; at < in.length; at += pieceBytes) {
            byte[] piece = java.util.Arrays.copyOfRange(in, at, Math.min(in.length, at + pieceBytes));
            out.writeBytes(r.process(piece));
        }
        out.writeBytes(r.flush());
        return out.toByteArray();
    }

    @Test
    void reframerCutsAnyPiecesIntoExactPrismaFramesAndKeepsTheRemainder() {
        Reframer framer = new Reframer(1024);
        byte[] data = new byte[1024 * 2 + 100];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        int frames = 0;
        for (int at = 0; at < data.length; at += 320) {
            List<byte[]> out = framer.push(java.util.Arrays.copyOfRange(data, at, Math.min(data.length, at + 320)));
            for (byte[] f : out) {
                assertThat(f).hasSize(1024);
                joined.writeBytes(f);
                frames++;
            }
        }
        assertThat(frames).isEqualTo(2);
        joined.writeBytes(framer.drain());
        assertThat(joined.toByteArray()).isEqualTo(data);
        assertThat(framer.drain()).isEmpty();
    }

    @Test
    void mulawRoundTripStaysCloseAndSilenceDecodesToZero() {
        assertThat(Mulaw.decode((byte) 0xFF)).isZero();
        for (int s = -32000; s <= 32000; s += 251) {
            short back = Mulaw.decode(Mulaw.encode((short) s));
            // mu-law keeps about 13 bits near the top of the range and far more near zero.
            assertThat((double) back).isCloseTo(s, within(Math.max(16, Math.abs(s) * 0.04)));
        }
    }

    @Test
    void mulawDecodesKnownCodeWordsFromTheG711Table() {
        assertThat(Mulaw.decode((byte) 0x00)).isEqualTo((short) -32124);
        assertThat(Mulaw.decode((byte) 0x80)).isEqualTo((short) 32124);
        assertThat(Mulaw.decode((byte) 0x7F)).isZero();
        assertThat(Mulaw.decode(new byte[] {(byte) 0xFF, (byte) 0x80})).hasSize(4);
    }

    @Test
    void resamplingDownKeepsSpeechBandAndLength() {
        byte[] in = tone(440, 24000, 1.0, 0.5);
        byte[] out = resampleInPieces(in, 24000, 8000, 4096);
        assertThat(out.length / 2).isEqualTo(8000);
        assertThat(Pcm.rms(out, 400, 7600)).isCloseTo(0.5 / Math.sqrt(2), within(0.01));
    }

    @Test
    void resamplingDownRemovesToneAboveTheNewNyquist() {
        byte[] in = tone(6000, 24000, 1.0, 0.5); // would fold back to 2 kHz without a filter
        byte[] out = resampleInPieces(in, 24000, 8000, 4096);
        assertThat(Pcm.rms(out, 400, 7600)).isLessThan(0.01);
    }

    @Test
    void resamplingUpFromBrowserRatesWorks() {
        byte[] down = resampleInPieces(tone(300, 48000, 0.5, 0.4), 48000, 8000, 960);
        assertThat(down.length / 2).isEqualTo(4000);
        byte[] oddRate = resampleInPieces(tone(300, 44100, 0.5, 0.4), 44100, 8000, 882);
        assertThat(oddRate.length / 2).isEqualTo(4000);
        assertThat(Pcm.rms(oddRate, 400, 3600)).isCloseTo(0.4 / Math.sqrt(2), within(0.01));
        byte[] up = resampleInPieces(tone(300, 8000, 0.5, 0.4), 8000, 24000, 320);
        assertThat(up.length / 2).isEqualTo(12000);
    }

    @Test
    void resamplerGivesTheSameSamplesHoweverTheInputIsSplit() {
        byte[] in = tone(523, 24000, 0.3, 0.3);
        assertThat(resampleInPieces(in, 24000, 8000, 2)).isEqualTo(resampleInPieces(in, 24000, 8000, in.length));
    }

    @Test
    void sameRatePassesAudioThrough() {
        StreamResampler r = new StreamResampler(8000, 8000);
        byte[] in = tone(300, 8000, 0.1, 0.2);
        assertThat(r.process(in)).isEqualTo(in);
        assertThat(r.flush()).isEmpty();
    }

    @Test
    void wavDecodeHandlesTimbresOpenEndedHeader() {
        byte[] pcm = tone(300, 24000, 0.2, 0.3);
        for (int declared : new int[] {0, -1}) { // 0 and 0xFFFFFFFF both mean "until the end of the file"
            byte[] wav = Wav.encode(pcm, 24000);
            ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(40, declared);
            Wav.Decoded decoded = Wav.decode(wav);
            assertThat(decoded.sampleRate()).isEqualTo(24000);
            assertThat(decoded.pcm16le()).isEqualTo(pcm);
        }
    }

    @Test
    void wavEncodeRoundTrips() {
        byte[] pcm = tone(300, 8000, 0.05, 0.3);
        Wav.Decoded decoded = Wav.decode(Wav.encode(pcm, 8000));
        assertThat(decoded.sampleRate()).isEqualTo(8000);
        assertThat(decoded.pcm16le()).isEqualTo(pcm);
    }

    @Test
    void wavDecodeMixesStereoDown() {
        ByteBuffer b = ByteBuffer.allocate(44 + 8).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(44).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 2).putInt(8000).putInt(32000).putShort((short) 4)
                .putShort((short) 16).put("data".getBytes()).putInt(8)
                .putShort((short) 100).putShort((short) 300).putShort((short) -100).putShort((short) -300);
        Wav.Decoded decoded = Wav.decode(b.array());
        assertThat(Pcm.sample(decoded.pcm16le(), 0)).isEqualTo((short) 200);
        assertThat(Pcm.sample(decoded.pcm16le(), 1)).isEqualTo((short) -200);
    }
}
