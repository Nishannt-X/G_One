package com.example.g_one.agent.brain;

import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.core.Language;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Which offered language a reply is spoken in, read from the script the model wrote it in. The system prompt tells the
 * model to answer in the language the farmer last spoke (Hindi in Devanagari, Bengali in Bengali script, English in
 * Latin), so the script is a reliable signal and costs no extra model call. When the reply's script maps to the
 * current language's prefix (Hinglish "hi-en" and Devanagari, for example) the current language is kept.
 */
final class ReplyLanguage {
    private static final Map<Character.UnicodeScript, String> PREFIX = new EnumMap<>(Map.ofEntries(
            Map.entry(Character.UnicodeScript.LATIN, "en"),
            Map.entry(Character.UnicodeScript.DEVANAGARI, "hi"),
            Map.entry(Character.UnicodeScript.BENGALI, "bn"),
            Map.entry(Character.UnicodeScript.TAMIL, "ta"),
            Map.entry(Character.UnicodeScript.KANNADA, "kn"),
            Map.entry(Character.UnicodeScript.TELUGU, "te"),
            Map.entry(Character.UnicodeScript.GUJARATI, "gu"),
            Map.entry(Character.UnicodeScript.GURMUKHI, "pa"),
            Map.entry(Character.UnicodeScript.MALAYALAM, "ml"),
            Map.entry(Character.UnicodeScript.ORIYA, "or")));

    private static final Pattern HINGLISH = Pattern.compile(" (aap|aapko|aapka|mera|mere|mujhe|kya|kaise|kaisa|hai|hain|naam|bhav|kitna|kab|kaun"
            + "|nahi|nahin|haan|batao|bataiye|bolo|karna|chahiye|karu|karun|mein|wala|aur|kisan|fasal|gehun|pyaz|tamatar) ");
    private static final Pattern BENGLISH = Pattern.compile(" (ami|amar|apni|apnar|tumi|tomar|kemon|korbo|bolun|kothay|koto|acche|ache|achhe"
            + "|bhalo|korte|dhonnobad|bikri|kintu|naki|dhan|chasi) ");

    private static final Pattern ENGLISH_WORDS = Pattern.compile("(?<= )(what|is|are|the|how|my|i|you|do|does|did|want|to|of|in|at|for"
            + "|will|it|me|a|an|and|tell|who|can|should|hello|good|morning|thanks|thank|please|name|price|weather|sell)(?= )");

    private ReplyLanguage() {}

    static Language of(String text, Language current, Language.Registry registry) {
        Optional<String> prefix = dominantPrefix(text);
        if (prefix.isEmpty() || prefix.get().equals(FixedLines.prefix(current.code()))) return current;
        // Romanised Hindi or Bengali is still that language: an English voice would mangle it.
        if (prefix.get().equals("en") && !romanisedPrefix(text).isEmpty() && romanisedPrefix(text).equals(FixedLines.prefix(current.code()))) {
            return current;
        }
        return byPrefix(prefix.get(), registry).orElse(current);
    }

    /**
     * The language a caller's turn is in: by script, with romanised Hindi and Bengali told apart from English by their
     * common words. Returns the code to answer in, preferring the code-mixed language when that is what was spoken.
     */
    static Language ofTurn(String text, Language current, Language.Registry registry) {
        Optional<String> prefix = dominantPrefix(text);
        if (prefix.isEmpty()) return current;
        String p = prefix.get();
        if (p.equals("en")) {
            String roman = romanisedPrefix(text);
            int english = count(ENGLISH_WORDS, " " + TurnIntent.normalized(text) + " ");
            if (roman.isEmpty() && english == 0) return current;
            if (english > 0 && english >= Math.max(markers(HINGLISH, text), markers(BENGLISH, text))) {
                return sameFamily("en", current, registry);
            }
            if (roman.equals("hi")) return exactly("hi-en", registry).orElseGet(() -> sameFamily("hi", current, registry));
            if (roman.equals("bn")) return exactly("bn-en", registry).orElseGet(() -> sameFamily("bn", current, registry));
            return TurnIntent.normalized(text).split(" ").length <= 2 ? current : sameFamily("en", current, registry);
        }
        return sameFamily(p, current, registry);
    }

    private static Language sameFamily(String prefix, Language current, Language.Registry registry) {
        if (FixedLines.prefix(current.code()).equals(prefix)) return current;
        return byPrefix(prefix, registry).orElse(current);
    }

    private static Optional<Language> exactly(String code, Language.Registry registry) {
        return registry.all().stream().filter(l -> l.code().equals(code)).findFirst();
    }

    /** "hi" or "bn" when the Latin text has more of that language's common words than none, else empty. */
    static String romanisedPrefix(String text) {
        String t = " " + TurnIntent.normalized(text) + " ";
        int hi = count(HINGLISH, t);
        int bn = count(BENGLISH, t);
        if (hi == 0 && bn == 0) return "";
        return bn > hi ? "bn" : "hi";
    }

    /** True when Latin text reads as Hindi or Bengali written in English letters, with several of their common words. */
    static boolean clearlyRomanised(String text) {
        String t = " " + TurnIntent.normalized(text) + " ";
        return count(HINGLISH, t) >= 3 || count(BENGLISH, t) >= 3;
    }

    /** True when {@code text} is in the wrong script or language for a reply expected in {@code expected}. */
    static boolean wrongFor(String text, Language expected) {
        Optional<String> got = dominantPrefix(text);
        if (got.isEmpty() || TurnIntent.normalized(text).split(" ").length < 4) return false;
        String want = FixedLines.prefix(expected.code());
        if (!got.get().equals(want)) return true;
        return want.equals("en") && clearlyRomanised(text);
    }

    private static int markers(Pattern p, String text) {
        return count(p, " " + TurnIntent.normalized(text) + " ");
    }

    private static int count(Pattern p, String text) {
        return (int) p.matcher(text).results().count();
    }

    /** The first offered language with this prefix ("en" → "en-IN"). */
    static Optional<Language> byPrefix(String prefix, Language.Registry registry) {
        return registry.all().stream().filter(l -> FixedLines.prefix(l.code()).equals(prefix)).findFirst();
    }

    /** The prefix of the script with the most characters, counting vowel signs; empty when no mapped script appears. */
    static Optional<String> dominantPrefix(String text) {
        Map<Character.UnicodeScript, Integer> counts = new EnumMap<>(Character.UnicodeScript.class);
        text.codePoints().forEach(cp -> {
            Character.UnicodeScript s = Character.UnicodeScript.of(cp);
            if (PREFIX.containsKey(s)) counts.merge(s, 1, Integer::sum);
        });
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> PREFIX.get(e.getKey()));
    }
}
