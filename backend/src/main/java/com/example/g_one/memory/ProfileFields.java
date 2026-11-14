package com.example.g_one.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The farmer profile fields from docs/memory.md, as {@link com.example.g_one.core.ProfileFact#field()} names. A profile
 * holds one fact per field, so each crop is its own field, {@code crop:<name>} (for example {@code crop:red onion}),
 * which keeps a provenance per crop.
 *
 * <p>Every field here is about the farmer and is farmer-private: it reaches the farmer's own assistant and the console,
 * never the buyer's leg.
 */
public final class ProfileFields {
    public static final String NAME = "name";
    public static final String VILLAGE = "village";
    public static final String DISTRICT = "district";
    public static final String STATE = "state";
    public static final String USUAL_MANDI = "usualMandi";
    public static final String LANGUAGE = "language";
    /** How much they usually sell at once, free text: "40 quintal per sale". */
    public static final String TYPICAL_QUANTITY = "typicalQuantity";
    /** One of {@link #TRANSPORTS}. */
    public static final String TRANSPORT = "transport";
    /** One of {@link #PAYMENTS}. */
    public static final String PAYMENT_PREFERENCE = "paymentPreference";
    /** One of {@link #STORAGE}. */
    public static final String STORAGE_ACCESS = "storageAccess";
    public static final String CROP_PREFIX = "crop:";

    /** Single-valued fields; crops use {@link #crop}. */
    public static final Set<String> SINGLE = Set.of(NAME, VILLAGE, DISTRICT, STATE, USUAL_MANDI, LANGUAGE,
            TYPICAL_QUANTITY, TRANSPORT, PAYMENT_PREFERENCE, STORAGE_ACCESS);
    /** The single fields in the order the console and the prompt show them. */
    public static final List<String> ORDER = List.of(NAME, VILLAGE, DISTRICT, STATE, USUAL_MANDI, TYPICAL_QUANTITY,
            TRANSPORT, PAYMENT_PREFERENCE, STORAGE_ACCESS, LANGUAGE);
    /** The language codes the schema allows. */
    public static final Set<String> LANGUAGES = Set.of("HI", "EN", "HI_EN", "BN");
    /** Names and places, always stored in English letters ({@link EnglishLetters}). */
    public static final Set<String> PROPER_NAMES = Set.of(NAME, VILLAGE, DISTRICT, STATE, USUAL_MANDI);

    public static final List<String> TRANSPORTS = List.of("own vehicle", "needs truck", "buyer arranges");
    public static final List<String> PAYMENTS = List.of("cash", "UPI", "bank transfer", "on delivery");
    public static final List<String> STORAGE = List.of("has cold storage", "cold storage nearby", "no cold storage");

    private static final int MAX_VALUE_CHARS = 60;
    private static final int MAX_QUANTITY_CHARS = 40;
    /** Six or more digits, even with spaces or dashes: a phone, account or ID number. Never a profile value. */
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d(?:[\\s-]?\\d){5,}");
    private static final Map<String, String> TRANSPORT_WORDS = words(
            "own vehicle", "own vehicle", "own", "own vehicle", "own tractor", "own vehicle", "own truck", "own vehicle",
            "own tempo", "own vehicle", "needs truck", "needs truck", "need truck", "needs truck",
            "needs transport", "needs truck", "needs a truck", "needs truck", "hires truck", "needs truck",
            "buyer arranges", "buyer arranges", "buyer pickup", "buyer arranges", "buyer picks up", "buyer arranges",
            "buyer collects", "buyer arranges");
    private static final Map<String, String> PAYMENT_WORDS = words(
            "cash", "cash", "upi", "UPI", "gpay", "UPI", "phonepe", "UPI", "bank transfer", "bank transfer",
            "bank", "bank transfer", "neft", "bank transfer", "rtgs", "bank transfer", "on delivery", "on delivery",
            "cash on delivery", "on delivery", "payment on delivery", "on delivery");
    private static final Map<String, String> STORAGE_WORDS = words(
            "has cold storage", "has cold storage", "yes", "has cold storage", "cold storage yes", "has cold storage",
            "own cold storage", "has cold storage", "cold storage nearby", "cold storage nearby", "nearby",
            "cold storage nearby", "no cold storage", "no cold storage", "no", "no cold storage",
            "cold storage no", "no cold storage", "none", "no cold storage");

    /** A field name and the value to store under it. */
    public record Checked(String field, String value) {}

    private ProfileFields() {}

    /** The field for one crop, normalised: trimmed, lower case, single spaces. */
    public static String crop(String crop) {
        return CROP_PREFIX + normaliseCrop(crop);
    }

    public static boolean isCrop(String field) {
        return field.startsWith(CROP_PREFIX) && field.length() > CROP_PREFIX.length();
    }

    /**
     * Checks a field and value proposed by the LLM against the whitelist. Accepts {@code crops} or {@code crop} as the
     * field for one crop.
     *
     * @return the field name to store, or empty when the field is not allowed or the value is unusable
     */
    public static Optional<String> allowed(String field, String value) {
        return check(field, value).map(Checked::field);
    }

    /**
     * Checks a field and value against the whitelist and gives what to store: names and places in English letters,
     * a language code in upper case, transport, payment and storage as one of their fixed values, the quantity capped.
     *
     * @return empty when the field is not allowed or the value is unusable (too long, a long number, not one of the
     *     fixed values, or a name with no English letters left)
     */
    public static Optional<Checked> check(String field, String value) {
        if (field == null || value == null || value.isBlank() || value.strip().length() > MAX_VALUE_CHARS
                || LONG_NUMBER.matcher(value).find()) {
            return Optional.empty();
        }
        String f = field.strip();
        String v = value.strip().replaceAll("\\s+", " ");
        if (f.equals("crops") || f.equals("crop")) {
            return Optional.of(new Checked(crop(v), v));
        }
        if (isCrop(f)) {
            return Optional.of(new Checked(crop(f.substring(CROP_PREFIX.length())), v));
        }
        if (!SINGLE.contains(f)) {
            return Optional.empty();
        }
        return value(f, v).map(stored -> new Checked(f, stored));
    }

    /** The value to store for single field {@code field}, or empty when it is not usable there. */
    static Optional<String> value(String field, String value) {
        if (PROPER_NAMES.contains(field)) {
            return EnglishLetters.name(value);
        }
        return switch (field) {
            case LANGUAGE -> Optional.of(value.toUpperCase(Locale.ROOT)).filter(LANGUAGES::contains);
            case TRANSPORT -> fixed(TRANSPORT_WORDS, value);
            case PAYMENT_PREFERENCE -> fixed(PAYMENT_WORDS, value);
            case STORAGE_ACCESS -> fixed(STORAGE_WORDS, value);
            case TYPICAL_QUANTITY -> Optional.of(value)
                    .filter(v -> v.length() <= MAX_QUANTITY_CHARS && v.chars().anyMatch(Character::isDigit)
                            && !EnglishLetters.hasNonLatinLetters(v));
            default -> Optional.of(value);
        };
    }

    private static Optional<String> fixed(Map<String, String> words, String value) {
        String key = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").strip();
        return Optional.ofNullable(words.get(key));
    }

    private static Map<String, String> words(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return Map.copyOf(m);
    }

    private static String normaliseCrop(String crop) {
        return crop.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
