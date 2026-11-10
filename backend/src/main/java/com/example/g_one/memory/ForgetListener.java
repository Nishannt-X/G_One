package com.example.g_one.memory;

import com.example.g_one.core.CallerKey;

/** Told after the console forgets a farmer, so other stores can drop what they keep under that caller key. */
@FunctionalInterface
public interface ForgetListener {
    void forgotten(CallerKey key);
}
