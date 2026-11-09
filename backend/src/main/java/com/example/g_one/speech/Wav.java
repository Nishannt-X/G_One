package com.example.g_one.speech;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Reads and writes plain 16-bit PCM WAV. {@link #validate} checks an uploaded recording at the trust boundary;
 * {@link #decode} also accepts the open-ended length headers Timbre returns.
 */
public final class Wav {
    private static final double MIN_SECONDS = 0.3;
    private static final double TOLERANCE_SECONDS = 0.5;

    private static final int HEADER_BYTES = 44;

    /** Mono PCM16 little-endian audio and its sample rate. */
    public record Decoded(byte[] pcm16le, int sampleRate) {}

    private Wav() {}

    /**
     * Extracts the audio, mixed down to mono. A data length of zero, 0xFFFFFFFF, or longer than the bytes present means
     * "until the end of the file", which is what Timbre's streaming writer leaves in the header.
     *
     * @throws BadAudioException {@code bad_audio} when the bytes are not 16-bit PCM WAV
     */
    public static Decoded decode(byte[] b) {
        if (b == null || b.length < HEADER_BYTES || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) {
            throw bad("Audio must be a WAV file.");
        }
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int channels = 0;
        int rate = 0;
        int bits = 0;
        long pos = 12;
        while (pos + 8 <= b.length) {
            int at = (int) pos;
            long size = buf.getInt(at + 4) & 0xFFFFFFFFL;
            if (tag(b, at, "fmt ") && at + 24 <= b.length) {
                int format = buf.getShort(at + 8) & 0xffff;
                if (format != 1 && format != 0xFFFE) throw bad("WAV must be uncompressed PCM.");
                channels = buf.getShort(at + 10);
                rate = buf.getInt(at + 12);
                bits = buf.getShort(at + 22);
            } else if (tag(b, at, "data")) {
                if (channels < 1 || rate <= 0 || bits != 16) throw bad("WAV must be 16-bit PCM.");
                long available = b.length - (pos + 8);
                long bytes = size == 0 || size > available ? available : size;
                return new Decoded(mono(b, at + 8, (int) bytes, channels), rate);
            }
            pos += 8 + size + (size & 1);
        }
        throw bad("WAV has no audio data.");
    }

    /** Wraps mono PCM16 little-endian audio in a standard 44-byte WAV header. */
    public static byte[] encode(byte[] pcm16le, int sampleRate) {
        ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES + pcm16le.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put(ascii("RIFF")).putInt(36 + pcm16le.length).put(ascii("WAVE")).put(ascii("fmt ")).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(sampleRate).putInt(sampleRate * 2)
                .putShort((short) 2).putShort((short) 16).put(ascii("data")).putInt(pcm16le.length).put(pcm16le);
        return out.array();
    }

    private static byte[] mono(byte[] b, int offset, int bytes, int channels) {
        int frames = bytes / (2 * channels);
        byte[] out = new byte[frames * 2];
        for (int f = 0; f < frames; f++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int at = offset + 2 * (f * channels + c);
                sum += (short) ((b[at] & 0xff) | (b[at + 1] << 8));
            }
            int value = sum / channels;
            out[2 * f] = (byte) value;
            out[2 * f + 1] = (byte) (value >> 8);
        }
        return out;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** @return the duration in seconds
     *  @throws BadAudioException {@code bad_audio} when the bytes are not a usable WAV, {@code too_long} when longer than the cap */
    public static double validate(byte[] b, int maxSeconds) {
        if (b == null || b.length < 44 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) {
            throw bad("Recording must be a WAV file.");
        }
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int channels = 0;
        int rate = 0;
        int bits = 0;
        int pos = 12;
        while (pos + 8 <= b.length) {
            long size = buf.getInt(pos + 4) & 0xFFFFFFFFL;
            if (tag(b, pos, "fmt ") && pos + 24 <= b.length) {
                if (buf.getShort(pos + 8) != 1) throw bad("WAV must be uncompressed PCM.");
                channels = buf.getShort(pos + 10);
                rate = buf.getInt(pos + 12);
                bits = buf.getShort(pos + 22);
            } else if (tag(b, pos, "data")) {
                if (channels < 1 || rate < 8000 || bits != 16) throw bad("WAV must be 16-bit PCM.");
                long available = Math.min(size, b.length - (pos + 8L));
                double seconds = available / (double) (rate * channels * 2);
                if (seconds < MIN_SECONDS) throw bad("Recording is too short.");
                if (seconds > maxSeconds + TOLERANCE_SECONDS) {
                    throw new BadAudioException("too_long", "Recording is longer than " + maxSeconds + " seconds.");
                }
                return seconds;
            }
            pos += 8 + (int) Math.min(size + (size & 1), b.length);
        }
        throw bad("WAV has no audio data.");
    }

    private static boolean tag(byte[] b, int off, String t) {
        for (int i = 0; i < 4; i++) {
            if (b[off + i] != t.charAt(i)) return false;
        }
        return true;
    }

    private static BadAudioException bad(String msg) {
        return new BadAudioException("bad_audio", msg);
    }
}
