package com.example.g_one.analytics;

/**
 * The memory store's forget, as the call records see it: called to complete a durable forget intent. Must be
 * idempotent (forgetting a caller twice, or one who has nothing stored, is harmless).
 */
@FunctionalInterface
public interface MemoryForget {
    void forget(String callerKey, String callId, long atMs);
}
