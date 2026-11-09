package com.example.g_one.speech.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Cuts mono PCM16 into pieces no longer than a byte limit, at the quietest moment near each limit so words stay whole. */
public final class PcmSplitter {
    private static final int WINDOW_MS = 20;
    private static final int SEARCH_MS = 1000;

    private PcmSplitter() {}

    public static List<byte[]> split(byte[] pcm, int sampleRate, int maxBytes) {
        int maxSamples = maxBytes / 2;
        int window = Math.max(1, sampleRate * WINDOW_MS / 1000);
        int search = sampleRate * SEARCH_MS / 1000;
        int total = pcm.length / 2;
        List<byte[]> pieces = new ArrayList<>();
        int from = 0;
        while (total - from > maxSamples) {
            int cut = quietest(pcm, Math.max(from + window, from + maxSamples - search), from + maxSamples, window);
            pieces.add(Arrays.copyOfRange(pcm, 2 * from, 2 * cut));
            from = cut;
        }
        if (from < total) {
            pieces.add(Arrays.copyOfRange(pcm, 2 * from, 2 * total));
        }
        return pieces;
    }

    /** The cut position in [low, high] whose preceding window is quietest; the latest wins a tie. */
    private static int quietest(byte[] pcm, int low, int high, int window) {
        int best = high;
        double bestLevel = Double.MAX_VALUE;
        for (int cut = high; cut >= low; cut -= window) {
            double level = Pcm.rms(pcm, cut - window, cut);
            if (level < bestLevel) {
                bestLevel = level;
                best = cut;
            }
        }
        return best;
    }
}
