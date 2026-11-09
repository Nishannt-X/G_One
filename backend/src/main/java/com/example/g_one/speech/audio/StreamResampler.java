package com.example.g_one.speech.audio;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Converts mono PCM16 between sample rates with a Hann-windowed sinc filter, so going down (24 kHz to 8 kHz) does not
 * fold high frequencies back into the speech band. Keeps state between calls: audio split into any pieces gives the
 * same output as one piece. Not thread-safe.
 */
public final class StreamResampler {
    private static final int ZERO_CROSSINGS = 12;
    private static final double CUTOFF_MARGIN = 0.95;

    private final int inRate;
    private final int outRate;
    private final int phases;
    private final int halfWidth;
    /** phases x (2 x halfWidth) coefficients, each row summing to 1. */
    private final double[][] filter;

    private short[] buffer = new short[4096];
    private int length;
    /** Absolute input index of buffer[0]; samples before the start of the stream read as zero. */
    private long base;
    private long totalIn;
    private long produced;

    public StreamResampler(int inRate, int outRate) {
        if (inRate <= 0 || outRate <= 0) {
            throw new IllegalArgumentException("Sample rates must be positive");
        }
        this.inRate = inRate;
        this.outRate = outRate;
        int divisor = gcd(inRate, outRate);
        phases = outRate / divisor;
        double cutoff = Math.min(1.0, (double) outRate / inRate) * CUTOFF_MARGIN;
        halfWidth = (int) Math.ceil(ZERO_CROSSINGS / cutoff);
        filter = new double[phases][2 * halfWidth];
        for (int phase = 0; phase < phases; phase++) {
            double sum = 0;
            for (int k = 0; k < 2 * halfWidth; k++) {
                double x = (k - halfWidth + 1) - (double) phase / phases;
                double value = sinc(cutoff * x) * hann(x / halfWidth);
                filter[phase][k] = value;
                sum += value;
            }
            for (int k = 0; k < 2 * halfWidth; k++) {
                filter[phase][k] /= sum;
            }
        }
    }

    /** Adds input and returns every output sample that can be computed so far. */
    public byte[] process(byte[] pcm16le) {
        if (inRate == outRate) {
            totalIn += pcm16le.length / 2;
            return pcm16le;
        }
        append(pcm16le);
        return run(false);
    }

    /** Ends the stream and returns the samples still held back by the filter. */
    public byte[] flush() {
        if (inRate == outRate) {
            return new byte[0];
        }
        return run(true);
    }

    private byte[] run(boolean finishing) {
        long target = totalIn * outRate / inRate;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            if (finishing && produced >= target) {
                break;
            }
            long scaled = produced * inRate;
            long centre = scaled / outRate;
            if (!finishing && centre + halfWidth >= totalIn) {
                break;
            }
            int phase = (int) ((scaled % outRate) * phases / outRate);
            double value = 0;
            for (int k = 0; k < 2 * halfWidth; k++) {
                value += filter[phase][k] * at(centre + k - halfWidth + 1);
            }
            short s = Pcm.clamp(value);
            out.write(s);
            out.write(s >> 8);
            produced++;
        }
        compact(produced * inRate / outRate - halfWidth);
        return out.toByteArray();
    }

    private short at(long index) {
        long offset = index - base;
        return offset < 0 || offset >= length ? 0 : buffer[(int) offset];
    }

    private void append(byte[] pcm) {
        int count = pcm.length / 2;
        if (length + count > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + count));
        }
        for (int i = 0; i < count; i++) {
            buffer[length + i] = Pcm.sample(pcm, i);
        }
        length += count;
        totalIn += count;
    }

    private void compact(long keepFrom) {
        int drop = (int) Math.min(length, Math.max(0, keepFrom - base));
        if (drop > 0) {
            System.arraycopy(buffer, drop, buffer, 0, length - drop);
            length -= drop;
            base += drop;
        }
    }

    private static double sinc(double x) {
        return x == 0 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
    }

    private static double hann(double x) {
        return Math.abs(x) >= 1 ? 0 : 0.5 + 0.5 * Math.cos(Math.PI * x);
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
