package com.example.g_one.core;

/** A live recognition stream for one leg. Safe to feed from one thread while events arrive on another. */
public interface SttSession extends AutoCloseable {
    /** Sends the next piece of the person's audio, in order. Frames after {@link #close()} are dropped. */
    void feed(AudioFrame frame);

    /**
     * Listens for {@code language} from now on, and stamps it on the turns that follow. Returns quickly. Audio fed while
     * an implementation changes over is kept and recognised afterwards. Harmless when nothing changes.
     */
    default void retarget(Language language) {}

    /** Stops recognition and releases the connection. Idempotent. No events arrive after it returns. */
    @Override
    void close();
}
