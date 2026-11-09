package com.example.g_one.agent.language;

import com.example.g_one.speech.turn.Phrases;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which language one finished turn is in, from the text alone: no network, no state. In order: a language the person
 * named; the script they spoke in; Latin words mixed into Devanagari or Bengali (Hinglish, Benglish); romanised Hindi or
 * Bengali; plain Latin text as English. Greetings, acknowledgements, numbers and single Latin words decide nothing.
 */
public final class LanguageDetector {
    /** How the language was found. */
    public enum Source { NAMED, SCRIPT, MIXED, ROMANISED, LATIN, UNKNOWN }

    /**
     * @param code the language code, or null when nothing could be worked out
     * @param hasContent true when the turn said something beyond naming a language: long enough to be worth answering
     */
    public record Detection(String code, Source source, boolean hasContent) {
        static final Detection UNKNOWN = new Detection(null, Source.UNKNOWN, false);

        public boolean unknown() {
            return code == null;
        }
    }

    /** A turn shorter than this says too little: no answer is owed to it and it is no evidence of a change of language. */
    public static final int MIN_CONTENT_WORDS = 3;
    /** Words left over, besides the language's name and polite filler, for a turn still to count as naming it. */
    private static final int MAX_WORDS_BESIDE_NAME = 2;
    /** Code-mixing needs at least this many words of the smaller script, so one borrowed word never counts as a mix. */
    private static final int MIN_MIXED_WORDS = 2;
    private static final double MIN_MIXED_SHARE = 0.25;
    private static final int MIN_ROMANISED_MARKERS = 2;
    private static final int MIN_ENGLISH_WORDS = 2;

    private static final Map<String, String> NAMES = normalizedKeys(Map.ofEntries(
            Map.entry("hindi", "hi-IN"), Map.entry("हिंदी", "hi-IN"), Map.entry("हिन्दी", "hi-IN"),
            Map.entry("हिंदि", "hi-IN"), Map.entry("হিন্দি", "hi-IN"),
            Map.entry("english", "en-IN"), Map.entry("angrezi", "en-IN"), Map.entry("angreji", "en-IN"),
            Map.entry("inglish", "en-IN"), Map.entry("अंग्रेज़ी", "en-IN"), Map.entry("अंग्रेजी", "en-IN"),
            Map.entry("इंग्लिश", "en-IN"), Map.entry("ইংরেজি", "en-IN"), Map.entry("ஆங்கிலம்", "en-IN"),
            Map.entry("ಇಂಗ್ಲಿಷ್", "en-IN"),
            Map.entry("hinglish", "hi-en"), Map.entry("हिंग्लिश", "hi-en"),
            Map.entry("bengali", "bn-IN"), Map.entry("bangla", "bn-IN"), Map.entry("bangali", "bn-IN"),
            Map.entry("बांग्ला", "bn-IN"), Map.entry("बंगाली", "bn-IN"), Map.entry("বাংলা", "bn-IN"),
            Map.entry("benglish", "bn-en"), Map.entry("banglish", "bn-en"),
            Map.entry("tamil", "ta-IN"), Map.entry("tamizh", "ta-IN"), Map.entry("thamizh", "ta-IN"),
            Map.entry("தமிழ்", "ta-IN"), Map.entry("तमिल", "ta-IN"),
            Map.entry("kannada", "kn-IN"), Map.entry("kanada", "kn-IN"), Map.entry("ಕನ್ನಡ", "kn-IN"),
            Map.entry("कन्नड़", "kn-IN"), Map.entry("कन्नड", "kn-IN")));

    /** Words around a language's name that say nothing else ("I want to speak", "में बात करेंगे"). */
    private static final Set<String> REQUEST_WORDS = normalized(Set.of(
            "i", "want", "to", "speak", "talk", "in", "please", "my", "language", "is", "would", "like", "the", "lets",
            "let", "us", "me", "sir", "madam", "only", "okay", "ok", "yes", "we", "can", "will", "do", "it", "use",
            "mujhe", "mera", "meri", "bhasha", "mein", "main", "baat", "karo", "karna", "karni", "kariye", "karenge",
            "bolo", "boliye", "bolna", "bolunga", "bolungi", "chahiye", "hai", "ji", "haan", "theek",
            "मुझे", "मेरी", "मेरा", "भाषा", "में", "मैं", "बात", "करो", "करना", "करनी", "करिए", "कीजिए", "करेंगे",
            "बोलो", "बोलिए", "बोलना", "बोलूँगा", "बोलूंगा", "बोलूँगी", "बोलूंगी", "चाहिए", "है", "जी", "हाँ", "ठीक",
            "আমি", "আমার", "ভাষা", "বলব", "বলতে", "চাই", "কথা", "হ্যাঁ", "ami", "bolbo", "bolte", "chai",
            "நான்", "பேச", "பேசுவேன்", "வேண்டும்", "ನಾನು", "ಮಾತನಾಡುತ್ತೇನೆ", "ಮಾತಾಡ್ತೀನಿ", "ಬೇಕು"));

    /** Said in any language, so they tell nothing about which one. */
    private static final Set<String> LATIN_GREETINGS = Set.of("hello", "helo", "hi", "hey", "namaste", "namaskar",
            "namaskara", "vanakkam", "nomoshkar", "nomoskar");

    private static final Set<String> HINDI_MARKERS = Set.of("aap", "aapko", "aapka", "mera", "mere", "mujhe", "kya",
            "kaise", "kaisa", "hai", "hain", "naam", "bhav", "kitna", "kab", "kaun", "nahi", "nahin", "haan", "batao",
            "bataiye", "bolo", "karna", "chahiye", "karu", "karun", "mein", "wala", "aur", "kisan", "fasal", "gehun",
            "pyaz", "tamatar");
    private static final Set<String> BENGALI_MARKERS = Set.of("ami", "amar", "apni", "apnar", "tumi", "tomar", "kemon",
            "korbo", "bolun", "kothay", "koto", "acche", "ache", "achhe", "bhalo", "korte", "dhonnobad", "bikri", "kintu",
            "naki", "dhan", "chasi");
    private static final Set<String> ENGLISH_WORDS = Set.of("what", "is", "are", "the", "how", "my", "i", "you", "do",
            "does", "did", "want", "to", "of", "in", "at", "for", "will", "it", "me", "a", "an", "and", "tell", "who", "can",
            "should", "good", "morning", "thanks", "thank", "please", "name", "price", "weather", "sell");

    private LanguageDetector() {}

    public static Detection detect(String text) {
        if (text == null || text.isBlank() || Phrases.isBackchannel(text)) return Detection.UNKNOWN;
        List<String> words = words(text);
        if (words.isEmpty()) return Detection.UNKNOWN;
        Detection named = named(words);
        if (named != null) return named;
        return byScript(words);
    }

    private static Detection named(List<String> words) {
        Set<String> codes = words.stream().map(NAMES::get).filter(c -> c != null).collect(Collectors.toSet());
        if (codes.size() != 1) return null; // none, or several: "Hindi or English" names no choice
        long other = words.stream().filter(w -> !NAMES.containsKey(w) && !REQUEST_WORDS.contains(w)).count();
        return other > MAX_WORDS_BESIDE_NAME ? null : new Detection(codes.iterator().next(), Source.NAMED, false);
    }

    private static Detection byScript(List<String> words) {
        int latin = 0;
        int devanagari = 0;
        int bengali = 0;
        int tamil = 0;
        int kannada = 0;
        for (String w : words) {
            switch (scriptOf(w)) {
                case LATIN -> latin++;
                case DEVANAGARI -> devanagari++;
                case BENGALI -> bengali++;
                case TAMIL -> tamil++;
                case KANNADA -> kannada++;
                default -> { }
            }
        }
        boolean content = words.size() >= MIN_CONTENT_WORDS;
        if (tamil > 0 && tamil >= latin + kannada) return new Detection("ta-IN", Source.SCRIPT, content);
        if (kannada > 0 && kannada >= latin + tamil) return new Detection("kn-IN", Source.SCRIPT, content);
        if (devanagari > 0 || bengali > 0) {
            boolean hindi = devanagari >= bengali;
            int native_ = Math.max(devanagari, bengali);
            if (mixed(native_, latin)) return new Detection(hindi ? "hi-en" : "bn-en", Source.MIXED, content);
            if (native_ >= latin) return new Detection(hindi ? "hi-IN" : "bn-IN", Source.SCRIPT, content);
        }
        return latin(words, latin, content);
    }

    /** Both scripts are there in strength: not one borrowed word either way. */
    private static boolean mixed(int native_, int latin) {
        int total = native_ + latin;
        int smaller = Math.min(native_, latin);
        return smaller >= MIN_MIXED_WORDS && (double) smaller / total >= MIN_MIXED_SHARE;
    }

    private static Detection latin(List<String> words, int latin, boolean content) {
        if (latin == 0) return Detection.UNKNOWN; // another script, or digits only
        if (words.size() == 1 && LATIN_GREETINGS.contains(words.get(0))) return Detection.UNKNOWN;
        int hindi = (int) words.stream().filter(HINDI_MARKERS::contains).count();
        int bengali = (int) words.stream().filter(BENGALI_MARKERS::contains).count();
        int english = (int) words.stream().filter(ENGLISH_WORDS::contains).count();
        int markers = Math.max(hindi, bengali);
        if (markers >= MIN_ROMANISED_MARKERS && markers > english) {
            return new Detection(bengali > hindi ? "bn-en" : "hi-en", Source.ROMANISED, content);
        }
        return words.size() < MIN_ENGLISH_WORDS ? Detection.UNKNOWN : new Detection("en-IN", Source.LATIN, content);
    }

    private static Character.UnicodeScript scriptOf(String word) {
        return word.codePoints().filter(Character::isLetter).mapToObj(Character.UnicodeScript::of).findFirst()
                .orElse(Character.UnicodeScript.UNKNOWN);
    }

    /** Words in lower case, composed the same way as the tables; a word with no letter (a number) is left out. */
    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        for (String w : normalize(text).split("[^\\p{L}\\p{M}\\p{N}]+")) {
            if (w.codePoints().anyMatch(Character::isLetter)) out.add(w);
        }
        return out;
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> normalizedKeys(Map<String, String> map) {
        return map.entrySet().stream().collect(Collectors.toUnmodifiableMap(e -> normalize(e.getKey()), Map.Entry::getValue, (a, b) -> a));
    }

    private static Set<String> normalized(Set<String> set) {
        return set.stream().map(LanguageDetector::normalize).collect(Collectors.toUnmodifiableSet());
    }
}
