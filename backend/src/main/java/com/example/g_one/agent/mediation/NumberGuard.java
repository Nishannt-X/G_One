package com.example.g_one.agent.mediation;

import com.example.g_one.agent.llm.SpokenNumbers;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that a translation carries the same numbers as its source: a wrong price is worse than no answer. Reads digits
 * (any script) and number words in English, Hindi, Bengali, Kannada, Tamil, and romanised Hindi and Bengali.
 *
 * <p>Small number words are often not numbers at all (Hindi "do" means "give", Bengali "noy" means "is not", "one" is an
 * article), so each side has <em>strong</em> numbers (digits, ten and up, or a small number before a unit) and
 * <em>all</em> numbers. A strong number on one side must appear among all the numbers on the other. A number the guard
 * cannot read makes the check fail, never pass, because the caller then asks for a second translation.
 */
final class NumberGuard {
    /** @param missing strong source numbers the translation lost; @param invented strong numbers it added */
    record Check(Set<String> missing, Set<String> invented) {
        boolean ok() {
            return missing.isEmpty() && invented.isEmpty();
        }
    }

    /** One number found in the text: where it sits, its value and the token right after it. */
    private record Span(int start, int end, String value, boolean strong, String next) {}

    private record Numbers(Set<String> strong, Set<String> all, List<Span> spans) {}

    private enum Kind { VALUE, SCALE, ADDS }

    private record Word(Kind kind, BigDecimal value) {}

    private static final Pattern TOKEN = Pattern.compile("\\p{Nd}+(?:,\\p{Nd}{2,3})*(?:\\.\\p{Nd}+)?|[\\p{L}\\p{M}]+");
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal YEAR_CENTURY = new BigDecimal("2000");

    private static final Map<String, Word> WORDS = new HashMap<>();
    private static final Map<String, Word> ROMAN_HI = new HashMap<>();
    private static final Map<String, Word> ROMAN_BN = new HashMap<>();
    private static final List<String> UNITS = new ArrayList<>();
    private static final List<String> QUANTITY_UNITS = new ArrayList<>();

    static {
        english();
        native_("hi");
        native_("bn");
        native_("kn");
        native_("ta");
        hindiExtras();
        bengaliExtras();
        kannadaExtras();
        tamilExtras();
        roman();
        for (String u : ("quintal qtl kg kilo ton tonne acre bigha rupee rs inr rupay rupaye rupya rupiya taka percent "
                + "क्विंटल कुंतल किलो टन एकड़ बीघा रुपये रुपए रुपया प्रतिशत "
                + "কুইন্টাল কেজি কিলো টন একর বিঘা টাকা শতাংশ "
                + "ಕ್ವಿಂಟಾಲ್ ಕ್ವಿಂಟಲ್ ಕೆಜಿ ಕಿಲೋ ಟನ್ ಎಕರೆ ರೂಪಾಯಿ ರೂ ಶೇಕಡ "
                + "குவிண்டால் கிலோ டன் ஏக்கர் ரூபாய் சதவீதம்").split(" ")) {
            UNITS.add(norm(u));
        }
        for (String u : ("quintal qtl kg kilo ton tonne acre bigha क्विंटल कुंतल किलो टन एकड़ बीघा কুইন্টাল কেজি কিলো টন একর "
                + "বিঘা ಕ್ವಿಂಟಾಲ್ ಕ್ವಿಂಟಲ್ ಕೆಜಿ ಕಿಲೋ ಟನ್ ಎಕರೆ குவிண்டால் கிலோ டன் ஏக்கர்").split(" ")) {
            QUANTITY_UNITS.add(norm(u));
        }
    }

    private NumberGuard() {}

    static Check check(String source, String fromCode, String translation, String toCode) {
        Numbers from = read(source, fromCode);
        Numbers to = read(translation, toCode);
        Set<String> missing = new HashSet<>(from.strong());
        missing.removeAll(to.all());
        Set<String> invented = new HashSet<>(to.strong());
        invented.removeAll(from.all());
        return new Check(missing, invented);
    }

    /**
     * The text with every strong number written as plain digits. Translators mangle spoken figures such as "আঠারোশো", but
     * they carry digits through unchanged, and the digits are then spelled out by our own code.
     */
    static String digitized(String text, String code) {
        List<Span> spans = read(text, code).spans();
        StringBuilder out = new StringBuilder();
        int at = 0;
        for (Span s : spans) {
            if (!s.strong()) continue;
            out.append(text, at, s.start()).append(s.value());
            at = s.end();
        }
        return out.append(text.substring(at)).toString();
    }

    /**
     * Checks a model's price against the words actually spoken. A price the turn or the recent lines do not contain is
     * replaced by the turn's only figure that is not a quantity, or dropped when there is no such single figure.
     *
     * @return the price to trust, or null when none can be trusted
     */
    static BigDecimal trustedPrice(BigDecimal price, String turn, String turnCode, List<String> recent, String... recentCodes) {
        if (price == null) return null;
        Numbers inTurn = read(turn, turnCode);
        String key = price.stripTrailingZeros().toPlainString();
        Set<String> spoken = new HashSet<>(inTurn.all());
        for (String line : recent) {
            for (String code : recentCodes) spoken.addAll(read(line, code).all());
        }
        if (spoken.contains(key)) return price;
        List<String> candidates = inTurn.spans().stream().filter(Span::strong)
                .filter(s -> QUANTITY_UNITS.stream().noneMatch(s.next()::startsWith))
                .map(Span::value).distinct().toList();
        return candidates.size() == 1 ? new BigDecimal(candidates.get(0)) : null;
    }

    /** Every number the guard can read in {@code text}, as plain decimal strings. */
    static Set<String> numbers(String text, String code) {
        return read(text, code).all();
    }

    private static Numbers read(String text, String code) {
        String lang = code == null ? "" : code.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        List<int[]> offsets = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            tokens.add(norm(m.group()));
            offsets.add(new int[] {m.start(), m.end()});
        }
        Set<String> strong = new HashSet<>();
        Set<String> all = new HashSet<>();
        List<Span> spans = new ArrayList<>();
        Run run = new Run(strong, all, spans);
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            String next = i + 1 < tokens.size() ? tokens.get(i + 1) : "";
            run.at(offsets.get(i));
            if (Character.isDigit(t.codePointAt(0))) {
                run.digits(digits(t), next);
                continue;
            }
            Word w = word(t, lang);
            if (w != null) {
                run.word(w, next);
            } else if (t.equals("and") && run.active && (word(next, lang) != null || next.equals("a"))) {
                if (next.equals("a") && i + 2 < tokens.size() && tokens.get(i + 2).equals("half")) {
                    run.addHalf();
                    run.at(offsets.get(i + 2));
                    i += 2;
                }
            } else {
                run.flush(t);
                if (lang.startsWith("en") && (t.equals("a") || t.equals("an"))) all.add("1");
            }
        }
        run.flush("");
        return new Numbers(strong, all, spans);
    }

    private static Word word(String t, String lang) {
        Word w = WORDS.get(t);
        if (w != null) return w;
        // Speech-to-text can romanise a Hindi or Bengali turn even when the leg is not labelled Hinglish.
        if (lang.startsWith("hi")) return ROMAN_HI.get(t);
        if (lang.startsWith("bn")) return ROMAN_BN.get(t);
        return null;
    }

    /** One number being assembled from consecutive words, Indian-grouping aware. */
    private static final class Run {
        private final Set<String> strong;
        private final Set<String> all;
        private BigDecimal total = BigDecimal.ZERO;
        private BigDecimal current = BigDecimal.ZERO;
        private BigDecimal lastScale;
        private BigDecimal pending = BigDecimal.ZERO;
        private boolean active;
        private boolean digit;
        private boolean wordOne;
        private String nextToken = "";
        private boolean adds;
        private int spanStart;
        private int spanEnd;
        private int[] position = {0, 0};
        private final List<Span> spans;

        Run(Set<String> strong, Set<String> all, List<Span> spans) {
            this.strong = strong;
            this.all = all;
            this.spans = spans;
        }

        void at(int[] offsets) {
            position = offsets;
        }

        private void cover() {
            if (!active) spanStart = position[0];
            spanEnd = position[1];
        }

        void digits(BigDecimal v, String next) {
            flush("");
            cover();
            active = true;
            digit = true;
            current = v;
            nextToken = next;
        }

        void word(Word w, String next) {
            nextToken = next;
            if (w.kind() == Kind.ADDS) {
                if (active) flush("");
                pending = w.value();
                adds = true;
                cover();
                return;
            }
            if (w.kind() == Kind.SCALE) {
                cover();
                scale(w.value());
                return;
            }
            BigDecimal v = w.value().add(pending);
            pending = BigDecimal.ZERO;
            if (active && isYear(v)) {
                spanEnd = position[1];
                current = YEAR_CENTURY.add(v);
                return;
            }
            if (active && !fits(v)) flush("");
            cover();
            adds = false;
            active = true;
            current = current.add(v);
            wordOne = total.signum() == 0 && current.compareTo(BigDecimal.ONE) == 0;
        }

        void addHalf() {
            current = current.add(HALF);
            wordOne = false;
        }

        /** "twenty twenty-six" spoken as a year: 2026, not 20 and 26. */
        private boolean isYear(BigDecimal v) {
            return !digit && total.signum() == 0 && current.compareTo(BigDecimal.valueOf(20)) == 0
                    && v.compareTo(BigDecimal.TEN) >= 0 && v.compareTo(BigDecimal.valueOf(100)) < 0;
        }

        private void scale(BigDecimal m) {
            if (adds && pending.signum() != 0) {
                current = BigDecimal.ONE.add(pending);
                pending = BigDecimal.ZERO;
                adds = false;
                active = true;
            }
            boolean hundred = m.compareTo(BigDecimal.valueOf(100)) == 0;
            if (active && lastScale != null && current.signum() == 0 && m.compareTo(lastScale) >= 0) flush("");
            if (hundred) {
                if (current.signum() == 0) current = m;
                else current = current.multiply(m);
            } else {
                total = total.add((current.signum() == 0 ? BigDecimal.ONE : current).multiply(m));
                current = BigDecimal.ZERO;
                lastScale = m;
            }
            active = true;
            wordOne = false;
        }

        private boolean fits(BigDecimal v) {
            if (digit) return false;
            if (current.signum() == 0) return lastScale != null && v.compareTo(lastScale) < 0;
            for (int place : new int[] {1000, 100, 10}) {
                BigDecimal p = BigDecimal.valueOf(place);
                if (current.remainder(p).signum() == 0 && current.compareTo(BigDecimal.valueOf(place == 10 ? 20 : place)) >= 0) {
                    return v.compareTo(p) < 0;
                }
            }
            return false;
        }

        void flush(String after) {
            if (active) {
                String next = after.isEmpty() ? nextToken : after;
                BigDecimal value = total.add(current);
                String key = value.stripTrailingZeros().toPlainString();
                all.add(key);
                boolean smallBeforeUnit = !wordOne && UNITS.stream().anyMatch(next::startsWith);
                boolean isStrong = digit || value.compareTo(BigDecimal.TEN) >= 0 || smallBeforeUnit;
                if (isStrong) strong.add(key);
                spans.add(new Span(spanStart, spanEnd, key, isStrong, next));
            }
            total = BigDecimal.ZERO;
            current = BigDecimal.ZERO;
            lastScale = null;
            active = false;
            digit = false;
            wordOne = false;
            nextToken = "";
        }
    }

    private static String norm(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replace("़", "").replace("়", "").replace("‌", "").replace("‍", "")
                .replace("ँ", "ं").replace("ঁ", "");
    }

    private static BigDecimal digits(String token) {
        StringBuilder out = new StringBuilder();
        token.codePoints().forEach(c -> {
            if (c == '.') out.append('.');
            else if (c != ',') out.append(Character.digit(c, 10));
        });
        return new BigDecimal(out.toString());
    }

    private static void put(Map<String, Word> map, String word, Kind kind, double value) {
        map.put(norm(word), new Word(kind, BigDecimal.valueOf(value).stripTrailingZeros()));
    }

    private static void value(String word, double v) {
        put(WORDS, word, Kind.VALUE, v);
    }

    private static void scale(String word, double v) {
        put(WORDS, word, Kind.SCALE, v);
    }

    private static void native_(String code) {
        for (int n = 1; n < 100; n++) value(SpokenNumbers.words(n, code), n);
    }

    private static void english() {
        String[] small = ("one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen "
                + "seventeen eighteen nineteen").split(" ");
        for (int i = 0; i < small.length; i++) value(small[i], i + 1);
        String[] tens = "twenty thirty forty fifty sixty seventy eighty ninety".split(" ");
        for (int i = 0; i < tens.length; i++) value(tens[i], (i + 2) * 10);
        value("zero", 0);
        String[] ordinals = ("first second third fourth fifth sixth seventh eighth ninth tenth eleventh twelfth thirteenth "
                + "fourteenth fifteenth sixteenth seventeenth eighteenth nineteenth twentieth").split(" ");
        for (int i = 0; i < ordinals.length; i++) value(ordinals[i], i + 1);
        value("thirtieth", 30);
        scale("hundred", 100);
        scale("thousand", 1_000);
        for (String s : "lakh lakhs lac lacs".split(" ")) scale(s, 100_000);
        for (String s : "crore crores".split(" ")) scale(s, 10_000_000);
        scale("million", 1_000_000);
    }

    private static void hindiExtras() {
        scale("सौ", 100);
        scale("हज़ार", 1_000);
        scale("लाख", 100_000);
        scale("करोड़", 10_000_000);
        value("छः", 6);
        value("छे", 6);
        value("छ", 6);
        value("अट्ठारह", 18);
        value("डेढ़", 1.5);
        value("ढाई", 2.5);
        put(WORDS, "साढ़े", Kind.ADDS, 0.5);
        put(WORDS, "सवा", Kind.ADDS, 0.25);
        put(WORDS, "पौने", Kind.ADDS, -0.25);
    }

    private static void bengaliExtras() {
        for (int n = 1; n < 100; n++) {
            String w = SpokenNumbers.words(n, "bn");
            value(w + "শো", n * 100);
            value(w + "শ", n * 100);
            for (String c : "টি টা টো টে".split(" ")) value(w + c, n);
        }
        for (String s : "শো শত শ".split(" ")) scale(s, 100);
        scale("হাজার", 1_000);
        scale("লাখ", 100_000);
        scale("লক্ষ", 100_000);
        scale("কোটি", 10_000_000);
        value("বিশ", 20);
        value("তিরিশ", 30);
        value("আঠেরো", 18);
        value("আঠার", 18);
        value("পনের", 15);
        value("ষোল", 16);
        value("সতের", 17);
        value("এগার", 11);
        value("তের", 13);
        value("চৌদ্দ", 14);
        value("দু", 2);
        value("দুশো", 200);
        value("দুশ", 200);
        value("দুটো", 2);
        value("দুটি", 2);
        value("তিনটে", 3);
        value("দেড়", 1.5);
        value("আড়াই", 2.5);
        put(WORDS, "সাড়ে", Kind.ADDS, 0.5);
    }

    private static void kannadaExtras() {
        String[] hundreds = "ನೂರು ಇನ್ನೂರು ಮುನ್ನೂರು ನಾನೂರು ಐನೂರು ಆರುನೂರು ಏಳುನೂರು ಎಂಟುನೂರು ಒಂಬೈನೂರು".split(" ");
        for (int i = 1; i < hundreds.length; i++) kannada(hundreds[i], Kind.VALUE, (i + 1) * 100);
        // Spoken Kannada also says the digit plus "nooru" for the hundreds that have a short form.
        kannada("ಎರಡುನೂರು", Kind.VALUE, 200);
        kannada("ಮೂರುನೂರು", Kind.VALUE, 300);
        kannada("ನಾಲ್ಕುನೂರು", Kind.VALUE, 400);
        kannada("ಐದುನೂರು", Kind.VALUE, 500);
        kannada("ಒಂಬತ್ತುನೂರು", Kind.VALUE, 900);
        kannada("ನೂರು", Kind.SCALE, 100);
        for (int n = 1; n < 100; n++) {
            String w = SpokenNumbers.words(n, "kn");
            if (!w.contains(" ")) kannada(w, Kind.VALUE, n);
        }
        for (String s : "ಸಾವಿರ ಸಾವಿರದ ಸಾವಿರಕ್ಕೆ ಸಾವಿರಗಳು ಸಾವಿರದಷ್ಟು".split(" ")) scale(s, 1_000);
        for (String s : "ಲಕ್ಷ ಲಕ್ಷದ ಲಕ್ಷಕ್ಕೆ".split(" ")) scale(s, 100_000);
        for (String s : "ಕೋಟಿ ಕೋಟಿಯ ಕೋಟಿಗೆ".split(" ")) scale(s, 10_000_000);
        value("ಒಂದೂವರೆ", 1.5);
        value("ಎರಡೂವರೆ", 2.5);
        value("ಮೂರೂವರೆ", 3.5);
        for (String stem : "ಇಪ್ಪತ್ತ ಮೂವತ್ತ ನಲವತ್ತ ಐವತ್ತ ಅರವತ್ತ ಎಪ್ಪತ್ತ ಎಂಬತ್ತ ತೊಂಬತ್ತ".split(" ")) {
            int tens = 20 + 10 * List.of("ಇಪ್ಪತ್ತ", "ಮೂವತ್ತ", "ನಲವತ್ತ", "ಐವತ್ತ", "ಅರವತ್ತ", "ಎಪ್ಪತ್ತ", "ಎಂಬತ್ತ", "ತೊಂಬತ್ತ").indexOf(stem);
            value(stem + "ಮೂರು", tens + 3);
            value(stem + "ನಾಲ್ಕು", tens + 4);
        }
    }

    /** The word, its combining stem (ನೂರು, ನೂರ) and the case endings used after a price or a count. */
    private static void kannada(String word, Kind kind, double v) {
        put(WORDS, word, kind, v);
        if (!word.endsWith("ು")) return;
        String stem = word.substring(0, word.length() - 1);
        if (kind == Kind.SCALE || stem.length() > 3) put(WORDS, stem, kind, v);
        for (String end : "ಕ್ಕೆ ರ ರಷ್ಟು ರಿಂದ ರಂತೆ".split(" ")) put(WORDS, stem + end, kind, v);
    }

    private static void tamilExtras() {
        String[] hundreds = "நூறு இருநூறு முந்நூறு நானூறு ஐந்நூறு அறுநூறு எழுநூறு எண்ணூறு தொள்ளாயிரம்".split(" ");
        String[] joined = "நூற்று இருநூற்று முந்நூற்று நானூற்று ஐந்நூற்று அறுநூற்று எழுநூற்று எண்ணூற்று தொள்ளாயிரத்து".split(" ");
        for (int i = 1; i < hundreds.length; i++) {
            value(hundreds[i], (i + 1) * 100);
            value(joined[i], (i + 1) * 100);
        }
        value("முன்னூறு", 300);
        value("ஐநூறு", 500);
        scale("நூறு", 100);
        scale("நூற்று", 100);
        scale("ஆயிரம்", 1_000);
        scale("ஆயிரத்து", 1_000);
        scale("ஆயிரத்தி", 1_000);
        scale("லட்சம்", 100_000);
        scale("லட்சத்து", 100_000);
        scale("கோடி", 10_000_000);
        scale("கோடியே", 10_000_000);
        value("ஒரு", 1);
        String[] tensWords = "இருபது முப்பது நாற்பது ஐம்பது அறுபது எழுபது எண்பது தொண்ணூறு".split(" ");
        String[] tensJoin = "இருபத்து முப்பத்து நாற்பத்து ஐம்பத்து அறுபத்து எழுபத்து எண்பத்து தொண்ணூற்று".split(" ");
        String[] tensGlue = "இருபத் முப்பத் நாற்பத் ஐம்பத் அறுபத் எழுபத் எண்பத் தொண்ணூற்".split(" ");
        String[] glued = {"தொன்று", "திரண்டு", "துமூன்று", "துநான்கு", "தைந்து", "தாறு", "தேழு", "தெட்டு", "தொன்பது"};
        for (int i = 0; i < tensWords.length; i++) {
            int tens = (i + 2) * 10;
            value(tensWords[i], tens);
            value(tensJoin[i], tens);
            value(tensWords[i] + "க்கு", tens);
            for (int u = 0; u < glued.length; u++) value(tensGlue[i] + glued[u], tens + u + 1);
        }
        String[] units = "இரண்டு மூன்று நான்கு ஐந்து ஆறு ஏழு எட்டு ஒன்பது".split(" ");
        String[] thousands = "இரண்டாயிரம் மூவாயிரம் நாலாயிரம் ஐயாயிரம் ஆறாயிரம் ஏழாயிரம் எட்டாயிரம் ஒன்பதாயிரம்".split(" ");
        for (int i = 0; i < thousands.length; i++) {
            put(WORDS, thousands[i], Kind.VALUE, (i + 2) * 1000.0);
            value(thousands[i].replace("யிரம்", "யிரத்து"), (i + 2) * 1000);
            value(units[i] + "க்கு", i + 2);
        }
        value("பத்தாயிரம்", 10_000);
    }

    private static void roman() {
        String[][] hi = {{"ek", "1"}, {"do", "2"}, {"teen", "3"}, {"tin", "3"}, {"char", "4"}, {"chaar", "4"}, {"paanch", "5"},
                {"panch", "5"}, {"chhe", "6"}, {"che", "6"}, {"saat", "7"}, {"aath", "8"}, {"nau", "9"}, {"das", "10"},
                {"gyarah", "11"}, {"barah", "12"}, {"baarah", "12"}, {"terah", "13"}, {"chaudah", "14"}, {"pandrah", "15"},
                {"solah", "16"}, {"satrah", "17"}, {"atharah", "18"}, {"attharah", "18"}, {"unnis", "19"}, {"bees", "20"},
                {"pachchis", "25"}, {"pachis", "25"}, {"tees", "30"}, {"chalis", "40"}, {"chaalis", "40"},
                {"pachas", "50"}, {"pachaas", "50"}, {"sattar", "70"}, {"assi", "80"}, {"nabbe", "90"}, {"dedh", "1.5"},
                {"dhai", "2.5"}, {"dhaai", "2.5"}};
        for (String[] e : hi) put(ROMAN_HI, e[0], Kind.VALUE, Double.parseDouble(e[1]));
        put(ROMAN_HI, "sau", Kind.SCALE, 100);
        for (String s : "hazaar hazar hajar".split(" ")) put(ROMAN_HI, s, Kind.SCALE, 1_000);
        for (String s : "crore karod karor".split(" ")) put(ROMAN_HI, s, Kind.SCALE, 10_000_000);
        put(ROMAN_HI, "sadhe", Kind.ADDS, 0.5);
        put(ROMAN_HI, "saadhe", Kind.ADDS, 0.5);
        put(ROMAN_HI, "sava", Kind.ADDS, 0.25);
        put(ROMAN_HI, "paune", Kind.ADDS, -0.25);
        String[][] bn = {{"ek", "1"}, {"dui", "2"}, {"tin", "3"}, {"char", "4"}, {"panch", "5"}, {"choy", "6"}, {"sat", "7"},
                {"at", "8"}, {"noy", "9"}, {"dosh", "10"}, {"egaro", "11"}, {"baro", "12"}, {"tero", "13"}, {"choddo", "14"},
                {"ponero", "15"}, {"sholo", "16"}, {"sotero", "17"}, {"atharo", "18"}, {"unish", "19"}, {"kuri", "20"},
                {"bish", "20"}, {"tirish", "30"}, {"chollish", "40"}, {"ponchash", "50"}, {"panchash", "50"},
                {"shat", "60"}, {"sottor", "70"}, {"ashi", "80"}, {"nobboi", "90"}, {"der", "1.5"}, {"arai", "2.5"}};
        for (String[] e : bn) put(ROMAN_BN, e[0], Kind.VALUE, Double.parseDouble(e[1]));
        for (String s : "sho shoto".split(" ")) put(ROMAN_BN, s, Kind.SCALE, 100);
        for (String s : "hajar hazar".split(" ")) put(ROMAN_BN, s, Kind.SCALE, 1_000);
        put(ROMAN_BN, "koti", Kind.SCALE, 10_000_000);
        put(ROMAN_BN, "share", Kind.ADDS, 0.5);
    }
}
