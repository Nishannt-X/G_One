package com.example.g_one.agent.mediation;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-code check: does a mediated turn call G-One by name, as a question or request to the assistant itself? Costs no
 * model call, so a turn that names G-One goes straight to the answer and never to translation.
 *
 * <p>The name is recognised as speech recognition writes it: "G-One", "G one", "G1", "Gee one", "ji one", "जी-वन",
 * "जी वन", "जी 1", "জি ওয়ান", "ஜி ஒன்", "ಜಿ ಒನ್". Two spellings are also ordinary words and count only at the very start
 * of the turn, followed by a pause or a question: "gone" ("the price has gone up") and "जीवन" ("life").
 *
 * <p>Addressed means the name is a vocative: at the start of the turn (after "hey", "ok", "अरे" and the like), right after
 * a pause (comma, full stop, "।"), right before a comma, or at the end of a question ("kitna maal hai G-One?"). A name used
 * in the third person is not addressed: "the G-One said it is fine", "ask G-One", "जी-वन ने कहा", "जी-वन से पूछो". Such a
 * turn is relayed like any other.
 */
public final class AddressDetector {
    /** Spellings that are only ever the assistant's name. */
    private static final Pattern STRONG = Pattern.compile(nfc(
            "(?<![\\p{L}\\p{M}\\p{Nd}])(?:"
                    + "(?:g|gee|ji|jee|zee)(?:[\\s.\\-]+(?:one|1|van|wan|won)|1)"
                    + "|geeone"
                    + "|जी(?:\\s*-\\s*|\\s+)(?:वन|वान)|जी\\s*[-]?\\s*(?:1|१|one)"
                    + "|জি\\s*[-]?\\s*(?:ওয়ান|ওয়ান|ওআন|ওয়ান|ওন|1|১|one)"
                    + "|ஜி\\s*[-]?\\s*(?:ஒன்|ஒண்|வன்|1|௧|one)"
                    + "|ஜீ\\s*[-]?\\s*(?:ஒன்|ஒண்|வன்|1|one)"
                    + "|ಜಿ\\s*[-]?\\s*(?:ಒನ್|ವನ್|1|೧|one)"
                    + "|ಜೀ\\s*[-]?\\s*(?:ಒನ್|ವನ್|1|one)"
                    + ")(?![\\p{L}\\p{M}\\p{Nd}])"),
            Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE);
    /** Spellings that are also ordinary words: "gone", "जीवन" (life), "জীবন" (life). */
    private static final Pattern WEAK = Pattern.compile(nfc(
            "(?<![\\p{L}\\p{M}\\p{Nd}])(?:gone|जीवन|জীবন|জিবন)(?![\\p{L}\\p{M}\\p{Nd}])"),
            Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{Nd}']+");
    private static final Pattern PAUSE = Pattern.compile("[,.!?।;:،…]");

    /** Words that may come before a vocative at the start: greetings, fillers, thanks. */
    private static final Set<String> OPENERS = words("hey", "hi", "hello", "helo", "ok", "okay", "oh", "o", "so", "and",
            "please", "plz", "listen", "excuse", "me", "sorry", "thanks", "thank", "you", "yes", "yeah", "haan", "han",
            "ha", "accha", "achha", "acha", "arre", "are", "arey", "suno", "suniye", "to", "toh", "bhai", "ji",
            "अरे", "हे", "ओ", "हेलो", "हलो", "हैलो", "सुनो", "सुनिए", "सुनिये", "अच्छा", "तो", "हाँ", "हां", "जी", "भाई",
            "धन्यवाद", "शुक्रिया", "ठीक", "है", "ওহে", "এই", "হ্যালো", "শুনুন", "শোনো", "আচ্ছা", "হ্যাঁ", "দাদা", "ধন্যবাদ",
            "ஹலோ", "சரி", "ஏய்", "நன்றி", "ಹಲೋ", "ಸರಿ", "ಏ", "ಧನ್ಯವಾದ");
    /** A word right before the name that makes it an object or a subject talked about: "ask G-One", "the G-One". */
    private static final Set<String> BEFORE_THIRD_PERSON = words("the", "ask", "tell", "told", "asked", "thank", "with", "from", "by",
            "about", "that", "this", "our", "your", "his", "her", "their", "called", "named", "trust", "using", "via",
            "per", "said", "says");
    /** A word right after the name that makes it a subject or object: "G-One said", "जी-वन ने", "जी-वन से पूछो". */
    private static final Set<String> AFTER_THIRD_PERSON = words("said", "says", "told", "tells", "is", "was", "will",
            "has", "had", "thinks", "knows", "and", "or", "ne", "ko", "se", "ka", "ki", "ke", "wala", "wali", "ने", "को",
            "से", "का", "की", "के", "वाला", "वाली", "বলেছে", "বলল", "এর", "কে", "কাছে", "கிட்ட", "இடம்",
            "சொன்னது", "சொன்னார்", "ಗೆ", "ಹೇಳಿದ್ದು", "ಹೇಳಿದರು", "ಅವರಿಗೆ");
    /** Question words and requests: what makes a turn-initial "gone" or "जीवन" a question to the assistant. */
    private static final Set<String> CUES = words("what", "whats", "what's", "how", "when", "where", "who", "which",
            "why", "tell", "please", "repeat", "say",
            "give", "check", "summarise", "summarize", "explain", "kya", "kitna", "kitni", "kitne", "kaun", "kab",
            "kahan", "kaisa", "kaise", "batao", "bataiye", "bataye", "bolo", "boliye", "sunao", "dobara",
            "phir", "zara", "jara", "क्या", "कितना", "कितनी", "कितने", "कौन", "कब", "कहाँ", "कहां", "कैसे", "बताओ",
            "बताइए", "बताइये", "बताना", "बोलो", "बोलिए", "सुनाओ", "दोबारा", "फिर", "ज़रा", "जरा", "কী", "কি", "কত", "কে",
            "কখন", "কোথায়", "কেমন", "বলো", "বলুন", "বলেন", "আবার", "என்ன", "எவ்வளவு", "எத்தனை", "யார்", "எப்போது",
            "எங்கே", "சொல்லு", "சொல்லுங்க", "சொல்லுங்கள்", "ಏನು", "ಎಷ್ಟು", "ಯಾರು", "ಯಾವಾಗ", "ಎಲ್ಲಿ", "ಹೇಳಿ", "ಹೇಳು");
    private static final Pattern QUESTION = Pattern.compile("[?？]");

    private AddressDetector() {}

    /** True when the turn calls G-One by name as someone being spoken to. */
    public static boolean addressed(String turn) {
        if (turn == null || turn.isBlank()) return false;
        String text = nfc(turn).toLowerCase(Locale.ROOT);
        boolean question = QUESTION.matcher(text).find() || hasCue(text);
        Matcher strong = STRONG.matcher(text);
        while (strong.find()) {
            if (vocative(text, strong.start(), strong.end(), question)) return true;
        }
        Matcher weak = WEAK.matcher(text);
        while (weak.find()) {
            char after = charAfter(text, weak.end());
            if (atStart(text, weak.start()) && (after == ',' || after == '?' || CUES.contains(nextWord(text, weak.end())))) {
                return true;
            }
        }
        return false;
    }

    private static boolean vocative(String text, int start, int end, boolean question) {
        char after = charAfter(text, end);
        boolean pauseBefore = PAUSE.matcher(String.valueOf(charBefore(text, start))).matches();
        // A comma or question mark right after the name sets it off as someone spoken to.
        boolean setOff = after == ',' || after == '،' || after == '?' || after == '？';
        if (!pauseBefore && BEFORE_THIRD_PERSON.contains(previousWord(text, start))) return false;
        if (!setOff && AFTER_THIRD_PERSON.contains(nextWord(text, end))) return false;
        if (atStart(text, start) || pauseBefore || setOff) return true;
        return question && text.substring(end).replaceAll("[\\p{P}\\s]", "").isEmpty();
    }

    /** Only openers ("hey", "ok", "अरे") come before this point. */
    private static boolean atStart(String text, int at) {
        String head = text.substring(0, at);
        if (PAUSE.matcher(head).find() && !head.replaceAll("[\\p{P}\\s]", "").isEmpty()) {
            // "haan, G-One" still counts: a pause after openers only.
            String[] parts = PAUSE.split(head, -1);
            for (String p : parts) {
                if (!onlyOpeners(p)) return false;
            }
            return true;
        }
        return onlyOpeners(head);
    }

    private static boolean onlyOpeners(String s) {
        Matcher m = WORD.matcher(s);
        while (m.find()) {
            if (!OPENERS.contains(m.group())) return false;
        }
        return true;
    }

    /** The nearest character before {@code at} that is not a space, or a space when there is none. */
    private static char charBefore(String text, int at) {
        for (int i = at - 1; i >= 0; i--) {
            if (!Character.isWhitespace(text.charAt(i))) return text.charAt(i);
        }
        return ' ';
    }

    private static char charAfter(String text, int at) {
        for (int i = at; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) return text.charAt(i);
        }
        return ' ';
    }

    private static String previousWord(String text, int at) {
        Matcher m = WORD.matcher(text.substring(0, at));
        String last = "";
        while (m.find()) last = m.group();
        return last;
    }

    private static String nextWord(String text, int at) {
        Matcher m = WORD.matcher(text.substring(at));
        return m.find() ? m.group() : "";
    }

    private static String nfc(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    private static Set<String> words(String... w) {
        return java.util.Arrays.stream(w).map(AddressDetector::nfc).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean hasCue(String text) {
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            if (CUES.contains(m.group())) return true;
        }
        return false;
    }
}
