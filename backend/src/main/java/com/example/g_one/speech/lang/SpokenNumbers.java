package com.example.g_one.speech.lang;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites digits as words before text goes to Timbre, which reads numbers better written out. Handles Indian grouping
 * (lakh, crore), rupees and paise, range prices, the farm units kg, quintal, ton, acre and bigha, and percent, for
 * Hindi, Hinglish, English, Bengali and Benglish. Languages without a word table pass through unchanged.
 */
public final class SpokenNumbers {
    private static final String NUMBER = "\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?";
    private static final String UNIT = "kgs?|kilo(?:gram)?s?|किलो(?:ग्राम)?|কেজি|কিলো"
            + "|quintals?|qtls?|क्विंटल|कुंतल|কুইন্টাল"
            + "|tonnes?|tons?|टन|টন"
            + "|acres?|एकड़|একর"
            + "|bighas?|बीघा|বিঘা";
    private static final String CURRENCY_AFTER = "rupees?|rupaye|rupaiya|रुपये|रुपए|रुपया|টাকা|taka";
    private static final String PERCENT = "%|percent|प्रतिशत|শতাংশ";
    private static final String SCALE = "lakhs?|lacs?|crores?|लाख|करोड़|লাখ|লক্ষ|কোটি";

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern BETWEEN_DIGITS = Pattern.compile("(?<=\\d)[/:](?=\\d)");
    private static final Pattern TOKEN = Pattern.compile(
            "(?<![\\p{L}\\p{M}\\p{N}.,])(?:(?<cur>₹|rs\\.?|inr|रु\\.?|रू\\.?)\\s*)?"
                    + "(?<num>" + NUMBER + ")"
                    + "(?:\\s*[-–]\\s*(?:₹\\s*)?(?<to>" + NUMBER + "))?"
                    + "(?:\\s*(?<scale>" + SCALE + ")(?![\\p{L}\\p{M}]))?"
                    + "(?:\\s*(?:(?<after>" + CURRENCY_AFTER + ")|(?<unit>" + UNIT + ")|(?<pct>" + PERCENT + "))(?![\\p{L}\\p{M}]))?"
                    + "(?![\\p{L}\\p{M}\\p{N}])",
            FLAGS);
    private static final Pattern PER_UNIT = Pattern.compile(
            "\\s*(?:/|\\bper\\s+)\\s*(?<unit>" + UNIT + ")(?![\\p{L}\\p{M}])", FLAGS);

    /** The words of one language. */
    private record Vocab(String[] under100, String[] hundreds, String thousand, String lakh, String crore, String point,
                         String percent, String rupees, String paise, String per, String to,
                         Map<String, String> units, String oneAndHalf, String twoAndHalf, String plusHalf) {}

    private static final Vocab ENGLISH = english();
    private static final Vocab HINDI = hindi();
    private static final Vocab HINGLISH = hinglish();
    private static final Vocab BENGALI = bengali();

    private SpokenNumbers() {}

    public static String normalize(String text, String languageCode) {
        Vocab vocab = vocabFor(text, languageCode);
        if (vocab == null) {
            return text;
        }
        String ascii = BETWEEN_DIGITS.matcher(asciiDigits(text)).replaceAll(" ");
        Matcher m = TOKEN.matcher(ascii);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(spell(m, vocab)));
        }
        m.appendTail(out);
        return perUnits(out.toString(), vocab);
    }

    private static Vocab vocabFor(String text, String languageCode) {
        return switch (languageCode.toLowerCase(Locale.ROOT)) {
            case "hi-in" -> HINDI;
            case "hi-en" -> hasDevanagari(text) ? HINDI : HINGLISH;
            case "en-in" -> ENGLISH;
            case "bn-in", "bn-en" -> BENGALI;
            default -> null;
        };
    }

    private static boolean hasDevanagari(String text) {
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.DEVANAGARI);
    }

    private static String asciiDigits(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp >= 0x0966 && cp <= 0x096F) {
                out.append((char) ('0' + cp - 0x0966));
            } else if (cp >= 0x09E6 && cp <= 0x09EF) {
                out.append((char) ('0' + cp - 0x09E6));
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    private static String spell(Matcher m, Vocab v) {
        String scale = m.group("scale");
        boolean money = m.group("cur") != null || m.group("after") != null;
        String first = amount(m.group("num"), scale, money, v);
        StringBuilder out = new StringBuilder(first);
        if (m.group("to") != null) {
            out.append(' ').append(v.to).append(' ').append(amount(m.group("to"), scale, money, v));
        }
        if (money && !endsWithPaise(m.group("num"), m.group("to"), scale)) {
            out.append(' ').append(v.rupees);
        }
        if (m.group("unit") != null) {
            out.append(' ').append(unitWord(m.group("unit"), v));
        }
        if (m.group("pct") != null) {
            out.append(' ').append(v.percent);
        }
        return out.toString();
    }

    /** A money amount with paise already says "rupees" in the middle. */
    private static boolean endsWithPaise(String num, String to, String scale) {
        return scale == null && hasPaise(to != null ? to : num);
    }

    private static boolean hasPaise(String raw) {
        int dot = raw.indexOf('.');
        return dot >= 0 && raw.length() - dot - 1 == 2 && !raw.endsWith("00");
    }

    private static String amount(String raw, String scale, boolean money, Vocab v) {
        String digits = raw.replace(",", "");
        if (scale != null) {
            BigDecimal value = new BigDecimal(digits).multiply(BigDecimal.valueOf(scaleOf(scale)));
            return whole(value.longValue(), v);
        }
        int dot = digits.indexOf('.');
        if (dot < 0) {
            return integer(digits, raw.contains(","), v);
        }
        String head = digits.substring(0, dot);
        String tail = digits.substring(dot + 1);
        if (money && tail.length() == 2) {
            long rupees = Long.parseLong(head);
            int paise = Integer.parseInt(tail);
            return paise == 0 ? whole(rupees, v) : whole(rupees, v) + " " + v.rupees + " " + whole(paise, v) + " " + v.paise;
        }
        if (tail.equals("5") && v.plusHalf != null && head.length() <= 2) {
            int n = Integer.parseInt(head);
            if (n == 1) return v.oneAndHalf;
            if (n == 2) return v.twoAndHalf;
            if (n > 2) return v.plusHalf + " " + whole(n, v);
        }
        return integer(head, false, v) + " " + v.point + " " + byDigit(tail, v);
    }

    private static long scaleOf(String scale) {
        String s = scale.toLowerCase(Locale.ROOT);
        return s.startsWith("cr") || s.equals("करोड़") || s.equals("কোটি") ? 1_00_00_000L : 1_00_000L;
    }

    private static String integer(String digits, boolean grouped, Vocab v) {
        boolean codeLike = digits.length() > 1 && digits.charAt(0) == '0';
        if (digits.length() > 15 || codeLike || (digits.length() >= 7 && !grouped)) {
            return byDigit(digits, v);
        }
        return whole(Long.parseLong(digits), v);
    }

    private static String byDigit(String digits, Vocab v) {
        StringBuilder out = new StringBuilder();
        for (char c : digits.toCharArray()) {
            if (out.length() > 0) out.append(' ');
            out.append(v.under100[c - '0']);
        }
        return out.toString();
    }

    private static String whole(long n, Vocab v) {
        if (n < 100) return v.under100[(int) n];
        StringBuilder out = new StringBuilder();
        long crore = n / 1_00_00_000L;
        n %= 1_00_00_000L;
        if (crore > 0) out.append(whole(crore, v)).append(' ').append(v.crore).append(' ');
        long lakh = n / 1_00_000L;
        n %= 1_00_000L;
        if (lakh > 0) out.append(v.under100[(int) lakh]).append(' ').append(v.lakh).append(' ');
        long thousand = n / 1_000L;
        n %= 1_000L;
        if (thousand > 0) out.append(v.under100[(int) thousand]).append(' ').append(v.thousand).append(' ');
        long hundred = n / 100L;
        n %= 100L;
        if (hundred > 0) out.append(v.hundreds[(int) hundred]).append(' ');
        if (n > 0) out.append(v.under100[(int) n]);
        return out.toString().strip();
    }

    private static String unitWord(String raw, Vocab v) {
        String s = raw.toLowerCase(Locale.ROOT);
        String key;
        if (s.startsWith("kg") || s.startsWith("kilo") || s.startsWith("किलो") || s.equals("কেজি") || s.equals("কিলো")) {
            key = "kg";
        } else if (s.startsWith("q") || s.equals("क्विंटल") || s.equals("कुंतल") || s.equals("কুইন্টাল")) {
            key = "quintal";
        } else if (s.startsWith("ton") || s.equals("टन") || s.equals("টন")) {
            key = "ton";
        } else if (s.startsWith("acre") || s.equals("एकड़") || s.equals("একর")) {
            key = "acre";
        } else {
            key = "bigha";
        }
        return v.units.get(key);
    }

    private static String perUnits(String text, Vocab v) {
        Matcher m = PER_UNIT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(" " + v.per + " " + unitWord(m.group("unit"), v)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static Vocab english() {
        String[] small = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
                "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
        String[] tens = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        String[] under100 = new String[100];
        for (int n = 0; n < 100; n++) {
            under100[n] = n < 20 ? small[n] : tens[n / 10] + (n % 10 == 0 ? "" : " " + small[n % 10]);
        }
        String[] hundreds = new String[10];
        for (int n = 1; n < 10; n++) {
            hundreds[n] = small[n] + " hundred";
        }
        return new Vocab(under100, hundreds, "thousand", "lakh", "crore", "point", "percent", "rupees", "paise", "per",
                "to", Map.of("kg", "kilos", "quintal", "quintal", "ton", "ton", "acre", "acre", "bigha", "bigha"),
                null, null, null);
    }

    private static Vocab hindi() {
        String[] w = ("शून्य एक दो तीन चार पाँच छह सात आठ नौ दस ग्यारह बारह तेरह चौदह पंद्रह सोलह सत्रह अठारह उन्नीस बीस "
                + "इक्कीस बाईस तेईस चौबीस पच्चीस छब्बीस सत्ताईस अट्ठाईस उनतीस तीस इकतीस बत्तीस तैंतीस चौंतीस पैंतीस छत्तीस "
                + "सैंतीस अड़तीस उनतालीस चालीस इकतालीस बयालीस तैंतालीस चवालीस पैंतालीस छियालीस सैंतालीस अड़तालीस उनचास पचास "
                + "इक्यावन बावन तिरपन चौवन पचपन छप्पन सत्तावन अट्ठावन उनसठ साठ इकसठ बासठ तिरसठ चौंसठ पैंसठ छियासठ सड़सठ अड़सठ "
                + "उनहत्तर सत्तर इकहत्तर बहत्तर तिहत्तर चौहत्तर पचहत्तर छिहत्तर सतहत्तर अठहत्तर उनासी अस्सी इक्यासी बयासी तिरासी "
                + "चौरासी पचासी छियासी सत्तासी अट्ठासी नवासी नब्बे इक्यानवे बानवे तिरानवे चौरानवे पचानवे छियानवे सत्तानवे अट्ठानवे "
                + "निन्यानवे").split(" ");
        String[] hundreds = new String[10];
        for (int n = 1; n < 10; n++) {
            hundreds[n] = w[n] + " सौ";
        }
        return new Vocab(w, hundreds, "हज़ार", "लाख", "करोड़", "दशमलव", "प्रतिशत", "रुपये", "पैसे", "प्रति", "से",
                Map.of("kg", "किलो", "quintal", "क्विंटल", "ton", "टन", "acre", "एकड़", "bigha", "बीघा"),
                "डेढ़", "ढाई", "साढ़े");
    }

    private static Vocab hinglish() {
        String[] w = ("shunya ek do teen chaar paanch chhah saat aath nau das gyaarah baarah terah chaudah pandrah solah "
                + "satrah athaarah unnees bees ikkees baaees teis chaubees pachchees chhabbees sattaaees atthaaees untees "
                + "tees ikattees battees taintees chauntees paintees chhattees saintees adtees untaalees chaalees iktaalees "
                + "bayaalees taintaalees chavaalees paintaalees chhiyaalees saintaalees adtaalees unchaas pachaas ikyaavan "
                + "baavan tirpan chauvan pachpan chhappan sattaavan atthaavan unsath saath iksath baasath tirsath chausath "
                + "painsath chhiyaasath sadsath adsath unhattar sattar ikhattar bahattar tihattar chauhattar pachhattar "
                + "chhihattar sathattar athhattar unaasi assi ikyaasi bayaasi tiraasi chauraasi pachaasi chhiyaasi sattaasi "
                + "atthaasi navaasi nabbe ikyaanve baanve tiraanve chauraanve pachaanve chhiyaanve sattaanve atthaanve "
                + "ninyaanve").split(" ");
        String[] hundreds = new String[10];
        for (int n = 1; n < 10; n++) {
            hundreds[n] = w[n] + " sau";
        }
        return new Vocab(w, hundreds, "hazaar", "lakh", "crore", "point", "percent", "rupaye", "paise", "per", "se",
                Map.of("kg", "kilo", "quintal", "quintal", "ton", "ton", "acre", "acre", "bigha", "bigha"),
                "dedh", "dhaai", "saadhe");
    }

    private static Vocab bengali() {
        String[] w = ("শূন্য এক দুই তিন চার পাঁচ ছয় সাত আট নয় দশ এগারো বারো তেরো চোদ্দো পনেরো ষোলো সতেরো আঠারো উনিশ কুড়ি "
                + "একুশ বাইশ তেইশ চব্বিশ পঁচিশ ছাব্বিশ সাতাশ আটাশ ঊনত্রিশ ত্রিশ একত্রিশ বত্রিশ তেত্রিশ চৌত্রিশ পঁয়ত্রিশ ছত্রিশ "
                + "সাঁইত্রিশ আটত্রিশ ঊনচল্লিশ চল্লিশ একচল্লিশ বিয়াল্লিশ তেতাল্লিশ চুয়াল্লিশ পঁয়তাল্লিশ ছেচল্লিশ সাতচল্লিশ আটচল্লিশ "
                + "ঊনপঞ্চাশ পঞ্চাশ একান্ন বাহান্ন তিপ্পান্ন চুয়ান্ন পঞ্চান্ন ছাপ্পান্ন সাতান্ন আটান্ন ঊনষাট ষাট একষট্টি বাষট্টি তেষট্টি "
                + "চৌষট্টি পঁয়ষট্টি ছেষট্টি সাতষট্টি আটষট্টি ঊনসত্তর সত্তর একাত্তর বাহাত্তর তিয়াত্তর চুয়াত্তর পঁচাত্তর ছিয়াত্তর সাতাত্তর "
                + "আটাত্তর ঊনআশি আশি একাশি বিরাশি তিরাশি চুরাশি পঁচাশি ছিয়াশি সাতাশি আটাশি ঊননব্বই নব্বই একানব্বই বিরানব্বই "
                + "তিরানব্বই চুরানব্বই পঁচানব্বই ছিয়ানব্বই সাতানব্বই আটানব্বই নিরানব্বই").split(" ");
        String[] hundreds = {"", "একশো", "দুশো", "তিনশো", "চারশো", "পাঁচশো", "ছয়শো", "সাতশো", "আটশো", "নয়শো"};
        return new Vocab(w, hundreds, "হাজার", "লাখ", "কোটি", "দশমিক", "শতাংশ", "টাকা", "পয়সা", "প্রতি", "থেকে",
                Map.of("kg", "কেজি", "quintal", "কুইন্টাল", "ton", "টন", "acre", "একর", "bigha", "বিঘা"),
                "দেড়", "আড়াই", "সাড়ে");
    }
}
