package com.example.g_one.core;

/** A way to deliver a turn as text instead of audio. Only the offline demo provides one. */
public interface TypedTurns {
    /** Treats {@code text} as a finished turn on this leg. Blank text and unknown legs are ignored. */
    void say(LegId legId, String text);
}
