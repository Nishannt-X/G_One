package com.example.g_one.analytics;

import java.util.Locale;

/** A party's answer to "may G-One remember this call?". Anything but a clear yes counts as no consent. */
public enum Consent {
    YES, NO, NONE;

    /** "YES", "NO" or "NONE" (any case); anything else, including null, is NONE. */
    public static Consent parse(String value) {
        if (value == null) {
            return NONE;
        }
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "YES" -> YES;
            case "NO" -> NO;
            default -> NONE;
        };
    }

    public boolean yes() {
        return this == YES;
    }
}
