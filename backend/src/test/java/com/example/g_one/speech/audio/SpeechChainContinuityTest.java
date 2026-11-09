package com.example.g_one.speech.audio;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.speech.Wav;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for "the assistant's voice buzzes": speech must leave the backend's conversion chain with no artefact
 * tied to how it was cut into pieces. A boundary bug (state lost per chunk, a split sample) shows up as a periodic jump at
 * the chunk size, heard as a buzz at chunk-rate harmonics. The signal is synthetic voiced speech, never a real recording.
 */
class SpeechChainContinuityTest {
    /** Message sizes Timbre really sends on the mu-law stream. */
    private static final int[] TIMBRE_CHUNKS = {960, 1120, 2240, 2080, 1403, 837, 677, 480};

    /** A harmonic stack with a slow vibrato around 210 Hz, so no harmonic sits still on a chunk-rate frequency. */
    private static byte[] voiced(int rate, double seconds) {
        int n = (int) (rate * seconds);
        byte[] pcm = new byte[n * 2];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / rate;
            phase += 2 * Math.PI * (210 + 30 * Math.sin(2 * Math.PI * 4.3 * t)) / rate;
            double v = 0;
            for (int h = 1; h <= 12 && h * 240 < rate / 2.0; h++) {
                v += Math.sin(h * phase) / h;
            }
            Pcm.put(pcm, i, Pcm.clamp(v * 9000));
        }
        return pcm;
    }

    private static byte[] join(ByteArrayOutputStream out) {
        return out.toByteArray();
    }

    private static byte[] resampleInTimbreChunks(byte[] pcm, int from, int to) {
        StreamResampler resampler = new StreamResampler(from, to);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int at = 0;
        for (int c = 0; at < pcm.length; c++) {
            int size = TIMBRE_CHUNKS[c % TIMBRE_CHUNKS.length] & ~1;
            int end = Math.min(pcm.length, at + size);
            out.writeBytes(resampler.process(Arrays.copyOfRange(pcm, at, end)));
            at = end;
        }
        out.writeBytes(resampler.flush());
        return join(out);
    }

    private static byte[] reframedInPieces(byte[] pcm, int frameBytes) {
        Reframer framer = new Reframer(frameBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int at = 0;
        for (int c = 0; at < pcm.length; c++) {
            int end = Math.min(pcm.length, at + TIMBRE_CHUNKS[c % TIMBRE_CHUNKS.length]);
            for (byte[] frame : framer.push(Arrays.copyOfRange(pcm, at, end))) {
                assertThat(frame).hasSize(frameBytes);
                out.writeBytes(frame);
            }
            at = end;
        }
        out.writeBytes(framer.drain());
        return join(out);
    }

    /** The largest sample-to-sample jump, over indices where {@code atBoundary} is as given. */
    private static int maxJump(byte[] pcm, int frameSamples, boolean atBoundary) {
        int worst = 0;
        for (int i = 1; i < pcm.length / 2; i++) {
            if ((i % frameSamples == 0) == atBoundary) {
                worst = Math.max(worst, Math.abs(Pcm.sample(pcm, i) - Pcm.sample(pcm, i - 1)));
            }
        }
        return worst;
    }

    /** Power of one frequency (Goertzel), normalised by the number of samples. */
    private static double powerAt(byte[] pcm, int rate, double hz) {
        double w = 2 * Math.PI * hz / rate;
        double coeff = 2 * Math.cos(w);
        double s1 = 0;
        double s2 = 0;
        int n = pcm.length / 2;
        for (int i = 0; i < n; i++) {
            double s = Pcm.sample(pcm, i) + coeff * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        return (s1 * s1 + s2 * s2 - coeff * s1 * s2) / n / n;
    }

    /** How far the chunk-rate comb stands above the frequencies halfway between its teeth, in dB. */
    private static double combDb(byte[] pcm, int rate, double periodHz) {
        double on = 0;
        double off = 0;
        for (double hz = periodHz; hz < rate / 2.0 - periodHz; hz += periodHz) {
            on += powerAt(pcm, rate, hz);
            off += powerAt(pcm, rate, hz + periodHz / 2);
        }
        return 10 * Math.log10(on / off);
    }

    private static void assertNoBoundaryArtefact(byte[] pcm, int rate, int frameSamples) {
        assertThat(maxJump(pcm, frameSamples, true))
                .as("jump at frame boundaries against jumps elsewhere")
                .isLessThanOrEqualTo((int) (maxJump(pcm, frameSamples, false) * 1.05));
        // The voice itself has some energy on this comb; only what the chain adds on top of it counts.
        double reference = combDb(voiced(rate, pcm.length / 2.0 / rate), rate, (double) rate / frameSamples);
        assertThat(combDb(pcm, rate, (double) rate / frameSamples) - reference)
                .as("comb at the frame rate, in dB above the clean voice").isLessThan(1.5);
    }

    @Test
    void wavWithOpenEndedHeaderDecodesToExactlyTheAudio() {
        byte[] pcm = voiced(24_000, 0.5);
        byte[] wav = Wav.encode(pcm, 24_000);
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 0xFFFFFFFF).putInt(40, 0xFFFFFFFF);

        Wav.Decoded decoded = Wav.decode(wav);

        assertThat(decoded.sampleRate()).isEqualTo(24_000);
        assertThat(decoded.pcm16le()).isEqualTo(pcm);
    }

    @Test
    void mulawStreamCutAnywhereGivesTheSameSpeechWithNoBoundaryArtefact() {
        byte[] whole = Mulaw.decode(Mulaw.encode(voiced(8000, 3)));

        byte[] mulaw = Mulaw.encode(voiced(8000, 3));
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int at = 0;
        for (int c = 0; at < mulaw.length; c++) {
            int end = Math.min(mulaw.length, at + TIMBRE_CHUNKS[c % TIMBRE_CHUNKS.length]);
            decoded.writeBytes(Mulaw.decode(Arrays.copyOfRange(mulaw, at, end)));
            at = end;
        }

        assertThat(decoded.toByteArray()).isEqualTo(whole);
        byte[] framed = reframedInPieces(decoded.toByteArray(), 320);
        assertThat(framed).isEqualTo(whole);
        assertNoBoundaryArtefact(framed, 8000, 160);
    }

    @Test
    void resamplingTimbreChunksMatchesResamplingWholeAndKeepsTheVoiceContinuous() {
        byte[] voice = voiced(24_000, 3);
        byte[] whole = resampleInTimbreChunks(voice, 24_000, 8000);

        StreamResampler once = new StreamResampler(24_000, 8000);
        ByteArrayOutputStream one = new ByteArrayOutputStream();
        one.writeBytes(once.process(voice));
        one.writeBytes(once.flush());

        assertThat(whole).isEqualTo(one.toByteArray());
        assertNoBoundaryArtefact(whole, 8000, 160);
    }

    @Test
    void widebandSpeechKeepsItsChunksUntouchedAndContinuous() {
        byte[] voice = voiced(24_000, 3);
        byte[] passed = resampleInTimbreChunks(voice, 24_000, 24_000);

        assertThat(passed).isEqualTo(voice);
        byte[] framed = reframedInPieces(passed, 960);
        assertThat(framed).isEqualTo(voice);
        assertNoBoundaryArtefact(framed, 24_000, 480);
        // 24 kHz has bandwidth a phone line cannot carry; the point of going wideband.
        assertThat(powerAt(framed, 24_000, 2640)).isPositive();
    }
}
