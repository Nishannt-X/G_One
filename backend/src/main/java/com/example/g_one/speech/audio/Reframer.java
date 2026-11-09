package com.example.g_one.speech.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Cuts a byte stream arriving in any size of piece into frames of exactly {@code frameBytes}, keeping the remainder. */
public final class Reframer {
    private final byte[] pending;
    private int filled;

    public Reframer(int frameBytes) {
        if (frameBytes <= 0) {
            throw new IllegalArgumentException("frameBytes must be positive");
        }
        pending = new byte[frameBytes];
    }

    /** @return every whole frame now available, in order; possibly none */
    public List<byte[]> push(byte[] data) {
        List<byte[]> frames = new ArrayList<>();
        int at = 0;
        while (at < data.length) {
            int take = Math.min(pending.length - filled, data.length - at);
            System.arraycopy(data, at, pending, filled, take);
            filled += take;
            at += take;
            if (filled == pending.length) {
                frames.add(pending.clone());
                filled = 0;
            }
        }
        return frames;
    }

    /** Takes whatever is left, shorter than one frame and possibly empty. */
    public byte[] drain() {
        byte[] rest = Arrays.copyOf(pending, filled);
        filled = 0;
        return rest;
    }
}
