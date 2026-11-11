package com.example.g_one.agent.mediation;

import com.example.g_one.core.LegRole;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-code check: is a mediated turn for G-One rather than for the other party? Costs no model call, so a turn that
 * names G-One goes straight to G-One and never to translation.
 *
 * <p>The name is recognised as speech recognition writes it: "G-One", "G one", "G1", "Gee one", "ji one", "जी-वन",
 * "जी वन", "जी 1", "জি ওয়ান", "ஜி ஒன்", "ಜಿ ಒನ್". Two spellings are also ordinary words and count only at the very start
 * of the turn, set off by a comma or question mark with more after it, or followed by a request ("जीवन बताओ"): "gone"
 * ("the price has gone up") and "जीवन" ("life"), so "जीवन क्या है?" is not a call to G-One.
 *
 * <p>Each mention of the name is one of:
 * <ul>
 *   <li><b>reported</b>, never addressed: "the G-One said", "G-One said", "G-One can tell you", "I told G-One",
 *       "जी-वन ने कहा", "G-One ki baat", "জি ওয়ান বলেছে", "ஜி ஒன் சொன்னது";</li>
 *   <li><b>routed</b> through G-One, always addressed, because it is G-One's to handle and never the other party's:
 *       "ask G-One", "tell G-One", "जी-वन से पूछो", "G-One ko batao", "জি-ওয়ানের কাছে", "ஜி ஒன் கிட்ட", "ಜಿ ಒನ್ ಗೆ";</li>
 *   <li><b>vocative</b>, addressed: at the start (after "hey", "ok", "अरे" and the like), after a pause, before a comma
 *       or question mark, or at the end of a question;</li>
 *   <li><b>ambiguous</b>, anything else ("I really like G-One", "maybe G-One knows his rate"). A farmer's ambiguous
 *       mention is relayed. A buyer's is not: it may be asking for something private to the farmer, so it goes to G-One,
 *       whose answer to a buyer can only state what the buyer already heard.</li>
 * </ul>
 */
public final class AddressDetector {
    private enum Mention { VOCATIVE, ROUTED, REPORTED, AMBIGUOUS }

    /** Spellings that are only ever the assistant's name. */
    private static final Pattern STRONG = Pattern.compile(nfc(
            "(?<![\\p{L}\\p{M}\\p{Nd}])(?:"
                    + "(?:g|gee|ji|jee|zee)(?:[\\s.\\-]+(?:one|1|van|wan|won)|1)"
                    + "|geeone"
                    + "|जी(?:\\s*-\\s*|\\s+)(?:वन|वान)|जी\\s*[-]?\\s*(?:1|१|one)"
                    + "|জি\\s*[-]?\\s*(?:ওয়ান|ওয়ান|ওআন|ওয়ান|ওন|1|১|one)(?:ের|এর|কে)?"
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
    private static final Pattern BENGALI_CASE = Pattern.compile(nfc("(?:ের|এর|কে)$"));

    /** Words that may come before a vocative at the start: greetings, fillers, thanks. */
    private static final Set<String> OPENERS = words("hey", "hi", "hello", "helo", "ok", "okay", "oh", "o", "so", "and",
            "please", "plz", "listen", "excuse", "me", "sorry", "thanks", "thank", "you", "yes", "yeah", "haan", "han",
            "ha", "accha", "achha", "acha", "arre", "are", "arey", "suno", "suniye", "to", "toh", "bhai", "ji",
            "अरे", "हे", "ओ", "हेलो", "हलो", "हैलो", "सुनो", "सुनिए", "सुनिये", "अच्छा", "तो", "हाँ", "हां", "जी", "भाई",
            "धन्यवाद", "शुक्रिया", "ठीक", "है", "ওহে", "এই", "হ্যালো", "শুনুন", "শোনো", "আচ্ছা", "হ্যাঁ", "দাদা", "ধন্যবাদ",
            "ஹலோ", "சரி", "ஏய்", "நன்றி", "ಹಲೋ", "ಸರಿ", "ಏ", "ಧನ್ಯವಾದ");
    /** Right after the name, these make it reported speech or a possessive: "G-One said", "जी-वन ने", "G-One ki". */
    private static final Set<String> REPORTED_AFTER = words("said", "says", "told", "tells", "ne", "ka", "ki", "ke",
            "ने", "का", "की", "के", "বলেছে", "বলল", "বলেছেন", "বললেন", "சொன்னது", "சொன்னார்", "சொன்னாங்க",
            "ಹೇಳಿದ್ದು", "ಹೇಳಿದರು", "ಹೇಳಿದ್ದಾರೆ");
    /** Right before the name, these make it someone talked about: "the G-One", "I told G-One". */
    private static final Set<String> REPORTED_BEFORE = words("the", "told", "trust", "using", "via", "by", "from",
            "with", "about", "that", "this", "our", "your", "his", "her", "their", "called", "named", "said", "says",
            "per");
    /** A modal right after the name is a third-person statement ("G-One can tell you"), unless "you" follows it. */
    private static final Set<String> MODALS = words("can", "could", "will", "would", "should", "may", "might", "shall",
            "does", "did", "do");
    private static final Set<String> SECOND_PERSON = words("you", "u", "ya", "aap", "tum", "आप", "तुम", "আপনি", "তুমি",
            "நீங்க", "நீங்கள்", "நீ", "ನೀವು", "ನೀನು");
    /** Right after the name: a request routed to G-One ("जी-वन से पूछो", "G-One ko batao", "ஜி ஒன் கிட்ட"). */
    private static final Set<String> ROUTED_AFTER = words("se", "ko", "से", "को", "কাছে", "কে", "கிட்ட", "இடம்", "ಗೆ",
            "ಅವರಿಗೆ");
    /** Right before the name: a request routed to G-One ("ask G-One", "tell G-One"). */
    private static final Set<String> ROUTED_BEFORE = words("ask", "tell", "asking", "telling");
    /** Right after the name, these make it a subject talked about, which is ambiguous: "G-One knows", "G-One is". */
    private static final Set<String> THIRD_PERSON_AFTER = words("is", "was", "has", "had", "thinks", "knows", "and",
            "or", "wala", "wali", "वाला", "वाली");
    /** Requests that make a turn-initial "gone" or "जीवन" a call to the assistant. */
    private static final Set<String> IMPERATIVES = words("tell", "repeat", "say", "give", "check", "summarise",
            "summarize", "explain", "batao", "bataiye", "bataye", "bolo", "boliye", "sunao", "बताओ", "बताइए", "बताइये",
            "बोलो", "बोलिए", "सुनाओ", "বলো", "বলুন", "சொல்லு", "சொல்லுங்க", "சொல்லுங்கள்", "ಹೇಳಿ", "ಹೇಳು");
    /** Question words: a name at the very end of a question is a vocative ("kitna maal hai G-One"). */
    private static final Set<String> QUESTION_WORDS = words("what", "whats", "what's", "how", "when", "where", "who",
            "which", "why", "kya", "kitna", "kitni", "kitne", "kaun", "kab", "kahan", "kaisa", "kaise", "क्या", "कितना",
            "कितनी", "कितने", "कौन", "कब", "कहाँ", "कहां", "कैसे", "কী", "কি", "কত", "কে", "কখন", "কোথায়", "কেমন",
            "என்ன", "எவ்வளவு", "எத்தனை", "யார்", "எப்போது", "எங்கே", "ಏನು", "ಎಷ್ಟು", "ಯಾರು", "ಯಾವಾಗ", "ಎಲ್ಲಿ");
    private static final Pattern QUESTION = Pattern.compile("[?？]");

    private AddressDetector() {}

    /** True when {@code speaker}'s turn is for G-One and must not be relayed ({@link AddressDetector}). */
    public static boolean addressed(String turn, LegRole speaker) {
        if (turn == null || turn.isBlank()) return false;
        String text = nfc(turn).toLowerCase(Locale.ROOT);
        boolean question = QUESTION.matcher(text).find() || has(text, QUESTION_WORDS) || has(text, IMPERATIVES);
        Matcher strong = STRONG.matcher(text);
        while (strong.find()) {
            Mention m = mention(text, strong.start(), strong.end(), strong.group(), question);
            if (m == Mention.VOCATIVE || m == Mention.ROUTED) return true;
            if (m == Mention.AMBIGUOUS && speaker == LegRole.BUYER) return true;
        }
        Matcher weak = WEAK.matcher(text);
        while (weak.find()) {
            if (!atStart(text, weak.start())) continue;
            char after = charAfter(text, weak.end());
            boolean setOff = (after == ',' || after == '?') && !nextWord(text, weak.end()).isEmpty();
            if (setOff || IMPERATIVES.contains(nextWord(text, weak.end()))) return true;
        }
        return false;
    }

    private static Mention mention(String text, int start, int end, String name, boolean question) {
        char after = charAfter(text, end);
        boolean pauseBefore = PAUSE.matcher(String.valueOf(charBefore(text, start))).matches();
        // A comma or question mark right after the name sets it off as someone spoken to.
        boolean setOff = after == ',' || after == '،' || after == '?' || after == '？';
        String before = previousWord(text, start);
        String next = nextWord(text, end);
        if (!setOff && REPORTED_AFTER.contains(next)) return Mention.REPORTED;
        if (!setOff && MODALS.contains(next) && !SECOND_PERSON.contains(wordAfter(text, end, next))) return Mention.REPORTED;
        if (!pauseBefore && REPORTED_BEFORE.contains(before)) return Mention.REPORTED;
        if (BENGALI_CASE.matcher(name).find()) return Mention.ROUTED;
        if (!setOff && ROUTED_AFTER.contains(next)) return Mention.ROUTED;
        if (!pauseBefore && ROUTED_BEFORE.contains(before)) return Mention.ROUTED;
        if (setOff || pauseBefore) return Mention.VOCATIVE;
        if (THIRD_PERSON_AFTER.contains(next)) return Mention.AMBIGUOUS;
        if (atStart(text, start)) return Mention.VOCATIVE;
        if (question && text.substring(end).replaceAll("[\\p{P}\\s]", "").isEmpty()) return Mention.VOCATIVE;
        return Mention.AMBIGUOUS;
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

    /** The word after {@code first}, the word that follows {@code at}. */
    private static String wordAfter(String text, int at, String first) {
        int i = text.indexOf(first, at);
        return i < 0 ? "" : nextWord(text, i + first.length());
    }

    private static boolean has(String text, Set<String> words) {
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            if (words.contains(m.group())) return true;
        }
        return false;
    }
}
