package com.example.g_one.speech.audio;

/** G.711 mu-law, the format Timbre can emit directly at 8 kHz. */
public final class Mulaw {
    private static final int BIAS = 0x84;
    private static final int CLIP = 32635;

    private Mulaw() {}

    /** Decodes mu-law bytes to PCM16 little-endian; the result is twice as long as the input. */
    public static byte[] decode(byte[] mulaw) {
        byte[] pcm = new byte[mulaw.length * 2];
        for (int i = 0; i < mulaw.length; i++) {
            Pcm.put(pcm, i, decode(mulaw[i]));
        }
        return pcm;
    }

    public static short decode(byte value) {
        int u = ~value & 0xff;
        int magnitude = (((u & 0x0f) << 3) + BIAS) << ((u & 0x70) >> 4);
        return (short) ((u & 0x80) != 0 ? BIAS - magnitude : magnitude - BIAS);
    }

    /** Encodes PCM16 little-endian to mu-law; the result is half as long as the input. */
    public static byte[] encode(byte[] pcm) {
        byte[] out = new byte[pcm.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = encode(Pcm.sample(pcm, i));
        }
        return out;
    }

    public static byte encode(short sample) {
        int value = sample;
        int sign = (value >> 8) & 0x80;
        if (sign != 0) {
            value = -value;
        }
        value = Math.min(value, CLIP) + BIAS;
        int exponent = 7;
        for (int mask = 0x4000; (value & mask) == 0 && exponent > 0; exponent--, mask >>= 1) {
            // Finds the position of the highest set bit.
        }
        int mantissa = (value >> (exponent + 3)) & 0x0f;
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }
}
