package com.example.g_one.speech.turn;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the words of an utterance say about the turn: a short acknowledgement ("haan", "theek hai") is not an attempt to
 * take the floor, and speech that stops on a connective ("aur", "lekin", "toh") has more coming. Covers Hindi, Hinglish,
 * English, Bengali and Benglish, in Latin, Devanagari and Bengali script.
 */
public final class Phrases {
    private static final int MAX_BACKCHANNEL_WORDS = 4;

    private static final Set<String> BACKCHANNEL = Set.of(
            "haan", "han", "haa", "ha", "hanji", "haanji", "ji", "jee", "hmm", "hm", "hmmm", "mm", "mmm", "uh-huh",
            "accha", "achha", "acha", "achchha", "theek", "thik", "hai", "ok", "okay", "yes", "yeah", "yep", "right", "sure",
            "हाँ", "हां", "हा", "जी", "हम्म", "हूँ", "अच्छा", "ठीक", "है", "ओके", "सही",
            "হ্যাঁ", "হাঁ", "হুম", "জি", "আচ্ছা", "ঠিক", "আছে", "ওকে", "thik-ache", "ache", "hyan", "hya");

    private static final Set<String> CONTINUATION = Set.of(
            "aur", "lekin", "toh", "to", "magar", "kyunki", "kyonki", "ki", "ya", "phir", "ar", "kintu", "tai", "karon",
            "और", "लेकिन", "तो", "मगर", "क्योंकि", "कि", "या", "फिर",
            "আর", "কিন্তু", "তাই", "কারণ", "এবং", "বা", "তো",
            "and", "but", "so", "because", "or");

    private Phrases() {}

    /** Whether the whole utterance is only an acknowledgement. Blank text is not one. */
    public static boolean isBackchannel(String text) {
        List<String> words = words(text);
        return !words.isEmpty() && words.size() <= MAX_BACKCHANNEL_WORDS && BACKCHANNEL.containsAll(words);
    }

    /** Whether the utterance stops on a connective, so the person probably has more to say. A question is complete. */
    public static boolean endsMidThought(String text) {
        String stripped = text == null ? "" : text.strip();
        if (stripped.endsWith("?") || stripped.endsWith("؟")) {
            return false;
        }
        List<String> words = words(stripped);
        return !words.isEmpty() && CONTINUATION.contains(words.get(words.size() - 1));
    }

    private static List<String> words(String text) {
        if (text == null) {
            return List.of();
        }
        // Keeps letters, combining marks (Indic vowels) and hyphens; everything else, including the danda, separates words.
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}-]+"))
                .filter(w -> !w.isEmpty())
                .toList();
    }
}
