package com.example.g_one.memory;

import com.example.g_one.core.CallerKey;

/**
 * Told when the console or the "forget me" tool forgets a farmer, BEFORE memory forgets, so other stores (the call
 * records) drop what they keep under that caller key first and durably. Throw to refuse: then memory is not touched
 * and the forget is reported as failed. A listener may also record a durable intent to forget memory, so a crash
 * between its commit and the memory forget is completed later; the caller then reports "accepted, completing" when
 * the memory forget fails.
 */
@FunctionalInterface
public interface ForgetListener {
    void forgotten(CallerKey key);
}
