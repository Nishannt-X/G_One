package com.example.g_one.agent.deal;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Plain-code reading of a spoken yes or no, in English, Hindi, Hinglish and Bengali. Unclear means not a yes: this
 * guard fails closed, and it also vetoes a model that reads a conditional as a yes ("yes, but...", "haan agar...").
 * Used for the deal confirmation and for the consent to connect a buyer.
 */
public final class ReplyGuard {
    /** What a short reply meant. */
    public enum Reading { YES, NO, UNCLEAR }

    /** A longer sentence that happens to contain "okay" is not an answer; the model reads those. */
    static final int MAX_YES_WORDS = 7;

    private static final Set<String> YES = Set.of("yes", "yeah", "yep", "yup", "ok", "okay", "sure", "correct", "right",
            "agreed", "agree", "done", "confirm", "confirmed", "accepted", "accept", "haan", "han", "haa", "ha", "hanji",
            "theek", "thik", "sahi", "pakka", "manzoor", "manjoor", "bilkul", "हाँ", "हां", "हा", "ठीक", "सही", "पक्का",
            "मंज़ूर", "मंजूर", "बिल्कुल", "बिलकुल", "হ্যাঁ", "হাঁ", "ঠিক", "আচ্ছা", "রাজি",
            "ಹೌದು", "ಸರಿ", "ಆಯಿತು", "ಒಪ್ಪಿಗೆ", "ಓಕೆ", "ஆம்", "சரி", "ஓகே");
    private static final Set<String> NO = Set.of("no", "nope", "nah", "nahi", "nahin", "nai", "naa", "na", "mat", "मत", "नहीं",
            "नही", "ना", "না", "নয়", "নই",
            "ಇಲ್ಲ", "ಬೇಡ", "இல்லை", "வேண்டாம்");
    /** The words of the old negation guard, plus Bengali. Any of them makes a yes unclear. */
    private static final Set<String> CONDITIONAL = Set.of("not", "dont", "don't", "doesnt", "cannot", "cant", "can't",
            "wont", "won't", "if", "unless", "but", "maybe", "perhaps", "only", "except", "agar", "lekin", "par", "magar",
            "shayad", "sirf", "अगर", "लेकिन", "मगर", "परंतु", "शायद", "सिर्फ़", "सिर्फ", "যদি", "কিন্তু", "হয়তো", "শুধু",
            "ಆದರೆ", "ಮಾತ್ರ", "ஆனால்");
    private static final List<String> CONNECT_ASK = List.of("connect", "call karo", "call kar do", "call kijiye",
            "call kariye", "phone lagao", "phone laga do", "phone lagaiye", "laga do", "lagao", "milao", "baat karao",
            "baat karwao", "फ़ोन लगा", "फोन लगा", "लगा दो", "लगा दीजिए", "लगाइए", "लगाओ", "मिलाओ", "मिला दो",
            "बात करा", "बात करवा", "ফোন করুন", "কথা বলিয়ে");

    private ReplyGuard() {}

    /** Reads a reply. A question, a conditional, a mix of yes and no, or a long sentence is UNCLEAR. */
    public static Reading read(String text) {
        if (text == null || text.isBlank() || text.contains("?")) return Reading.UNCLEAR;
        Set<String> words = words(text);
        if (containsAny(words, CONDITIONAL)) return Reading.UNCLEAR;
        boolean yes = containsAny(words, YES);
        boolean no = containsAny(words, NO);
        if (yes == no) return Reading.UNCLEAR;
        if (no) return Reading.NO;
        return tokens(text).size() <= MAX_YES_WORDS ? Reading.YES : Reading.UNCLEAR;
    }

    /** True unless the text carries a condition, a negation or a question: a model's "yes" must also pass this. */
    public static boolean permitsYes(String text) {
        if (text == null || text.contains("?")) return false;
        Set<String> words = words(text);
        return !containsAny(words, CONDITIONAL) && !containsAny(words, NO);
    }

    /** An explicit yes, or a plain request to connect ("haan laga do", "unse baat karao"). */
    public static boolean isConsent(String text) {
        if (!permitsYes(text)) return false;
        if (read(text) == Reading.YES) return true;
        String lower = text.toLowerCase(Locale.ROOT);
        return CONNECT_ASK.stream().anyMatch(lower::contains);
    }

    private static boolean containsAny(Set<String> words, Set<String> list) {
        return words.stream().anyMatch(list::contains);
    }

    private static Set<String> words(String text) {
        return tokens(text).stream().collect(Collectors.toSet());
    }

    private static List<String> tokens(String text) {
        // Keep letters, combining vowel signs and the apostrophe; everything else separates words.
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{M}']+")).filter(w -> !w.isEmpty()).toList();
    }
}
