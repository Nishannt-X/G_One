package com.example.g_one.speech.audio;

/** Helpers for mono signed 16-bit little-endian PCM. */
public final class Pcm {
    private Pcm() {}

    public static short sample(byte[] pcm, int index) {
        return (short) ((pcm[2 * index] & 0xff) | (pcm[2 * index + 1] << 8));
    }

    public static void put(byte[] pcm, int index, short value) {
        pcm[2 * index] = (byte) value;
        pcm[2 * index + 1] = (byte) (value >> 8);
    }

    public static short clamp(double value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
    }

    /** Root mean square level of the samples from 0 (silence) to about 1 (full scale). */
    public static double rms(byte[] pcm) {
        return rms(pcm, 0, pcm.length / 2);
    }

    public static double rms(byte[] pcm, int fromSample, int toSample) {
        if (toSample <= fromSample) {
            return 0;
        }
        double sum = 0;
        for (int i = fromSample; i < toSample; i++) {
            double v = sample(pcm, i) / 32768.0;
            sum += v * v;
        }
        return Math.sqrt(sum / (toSample - fromSample));
    }
}
