package com.example.g_one.agent.llm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Numbers and dates written as words in Hindi, Bengali, Kannada, Tamil or English, for lines Timbre speaks. Built in code
 * so a price in a read-back never depends on a model. Indian grouping (lakh, crore). Any other language uses English
 * words, and dates are only spelled in Hindi, Bengali and English.
 */
public final class SpokenNumbers {
    private static final String[] HI = ("शून्य एक दो तीन चार पाँच छह सात आठ नौ दस ग्यारह बारह तेरह चौदह पंद्रह सोलह सत्रह अठारह "
            + "उन्नीस बीस इक्कीस बाईस तेईस चौबीस पच्चीस छब्बीस सत्ताईस अट्ठाईस उनतीस तीस इकतीस बत्तीस तैंतीस चौंतीस पैंतीस "
            + "छत्तीस सैंतीस अड़तीस उनतालीस चालीस इकतालीस बयालीस तैंतालीस चौवालीस पैंतालीस छियालीस सैंतालीस अड़तालीस उनचास पचास "
            + "इक्यावन बावन तिरपन चौवन पचपन छप्पन सत्तावन अट्ठावन उनसठ साठ इकसठ बासठ तिरसठ चौंसठ पैंसठ छियासठ सड़सठ अड़सठ "
            + "उनहत्तर सत्तर इकहत्तर बहत्तर तिहत्तर चौहत्तर पचहत्तर छिहत्तर सतहत्तर अठहत्तर उनासी अस्सी इक्यासी बयासी तिरासी "
            + "चौरासी पचासी छियासी सत्तासी अट्ठासी नवासी नब्बे इक्यानवे बानवे तिरानवे चौरानवे पचानवे छियानवे सत्तानवे अट्ठानवे "
            + "निन्यानवे").split(" ");
    private static final String[] BN = ("শূন্য এক দুই তিন চার পাঁচ ছয় সাত আট নয় দশ এগারো বারো তেরো চোদ্দ পনেরো ষোলো সতেরো আঠারো "
            + "উনিশ কুড়ি একুশ বাইশ তেইশ চব্বিশ পঁচিশ ছাব্বিশ সাতাশ আটাশ ঊনত্রিশ ত্রিশ একত্রিশ বত্রিশ তেত্রিশ চৌত্রিশ পঁয়ত্রিশ ছত্রিশ "
            + "সাঁইত্রিশ আটত্রিশ ঊনচল্লিশ চল্লিশ একচল্লিশ বিয়াল্লিশ তেতাল্লিশ চুয়াল্লিশ পঁয়তাল্লিশ ছেচল্লিশ সাতচল্লিশ আটচল্লিশ ঊনপঞ্চাশ "
            + "পঞ্চাশ একান্ন বাহান্ন তিপ্পান্ন চুয়ান্ন পঞ্চান্ন ছাপ্পান্ন সাতান্ন আটান্ন ঊনষাট ষাট একষট্টি বাষট্টি তেষট্টি চৌষট্টি পঁয়ষট্টি "
            + "ছেষট্টি সাতষট্টি আটষট্টি ঊনসত্তর সত্তর একাত্তর বাহাত্তর তিয়াত্তর চুয়াত্তর পঁচাত্তর ছিয়াত্তর সাতাত্তর আটাত্তর ঊনআশি আশি "
            + "একাশি বিরাশি তিরাশি চুরাশি পঁচাশি ছিয়াশি সাতাশি আটাশি ঊননব্বই নব্বই একানব্বই বিরানব্বই তিরানব্বই চুরানব্বই পঁচানব্বই "
            + "ছিয়ানব্বই সাতানব্বই আটানব্বই নিরানব্বই").split(" ");
    private static final String[] KN_UNITS = "ಶೂನ್ಯ ಒಂದು ಎರಡು ಮೂರು ನಾಲ್ಕು ಐದು ಆರು ಏಳು ಎಂಟು ಒಂಬತ್ತು ಹತ್ತು ಹನ್ನೊಂದು ಹನ್ನೆರಡು ಹದಿಮೂರು ಹದಿನಾಲ್ಕು ಹದಿನೈದು ಹದಿನಾರು ಹದಿನೇಳು ಹದಿನೆಂಟು ಹತ್ತೊಂಬತ್ತು".split(" ");
    private static final String[] KN_TENS = "ಇಪ್ಪತ್ತ ಮೂವತ್ತ ನಲವತ್ತ ಐವತ್ತ ಅರವತ್ತ ಎಪ್ಪತ್ತ ಎಂಬತ್ತ ತೊಂಬತ್ತ".split(" ");
    private static final String[] KN_JOIN = {"", "ೊಂದು", "ೆರಡು", "ಮೂರು", "ನಾಲ್ಕು", "ೈದು", "ಾರು", "ೇಳು", "ೆಂಟು", "ೊಂಬತ್ತು"};
    private static final String[] KN_HUNDREDS = "ನೂರು ಇನ್ನೂರು ಮುನ್ನೂರು ನಾನೂರು ಐನೂರು ಆರುನೂರು ಏಳುನೂರು ಎಂಟುನೂರು ಒಂಬೈನೂರು".split(" ");
    private static final String[] TA_UNITS = "பூஜ்ஜியம் ஒன்று இரண்டு மூன்று நான்கு ஐந்து ஆறு ஏழு எட்டு ஒன்பது பத்து பதினொன்று பன்னிரண்டு பதிமூன்று பதினான்கு பதினைந்து பதினாறு பதினேழு பதினெட்டு பத்தொன்பது".split(" ");
    private static final String[] TA_TENS = "இருபது முப்பது நாற்பது ஐம்பது அறுபது எழுபது எண்பது தொண்ணூறு".split(" ");
    private static final String[] TA_TENS_JOIN = "இருபத்து முப்பத்து நாற்பத்து ஐம்பத்து அறுபத்து எழுபத்து எண்பத்து தொண்ணூற்று".split(" ");
    private static final String[] TA_HUNDREDS = "நூறு இருநூறு முந்நூறு நானூறு ஐந்நூறு அறுநூறு எழுநூறு எண்ணூறு தொள்ளாயிரம்".split(" ");
    private static final String[] TA_HUNDREDS_JOIN = "நூற்று இருநூற்று முந்நூற்று நானூற்று ஐந்நூற்று அறுநூற்று எழுநூற்று எண்ணூற்று தொள்ளாயிரத்து".split(" ");
    private static final String[] HI_MONTHS = {"जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर",
            "अक्टूबर", "नवंबर", "दिसंबर"};
    private static final String[] BN_MONTHS = {"জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন", "জুলাই", "আগস্ট",
            "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর"};
    private static final Pattern DIGITS = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private SpokenNumbers() {}

    /** A whole number as words in the language of {@code code} ("hi-IN", "bn-IN", anything else English). */
    public static String words(long n, String code) {
        return switch (FixedLines.prefix(code)) {
            case "hi" -> indic(n, HI, " सौ", "हज़ार", "लाख", "करोड़", " ");
            case "bn" -> indic(n, BN, "শো", "হাজার", "লাখ", "কোটি", "");
            case "kn" -> kannada(n);
            case "ta" -> tamil(n);
            default -> SpokenEnglish.number(n);
        };
    }

    /** A price or quantity: a whole number as words, or the whole part, the word for "point" and each decimal digit. */
    public static String words(BigDecimal value, String code) {
        BigDecimal v = value.stripTrailingZeros();
        long whole = v.longValue();
        if (v.scale() <= 0) return words(whole, code);
        String point = switch (FixedLines.prefix(code)) {
            case "hi" -> "दशमलव";
            case "bn" -> "দশমিক";
            case "kn" -> "ದಶಮಾಂಶ";
            case "ta" -> "புள்ளி";
            default -> "point";
        };
        StringBuilder out = new StringBuilder(words(whole, code)).append(' ').append(point);
        String digits = v.toPlainString().substring(v.toPlainString().indexOf('.') + 1);
        for (char c : digits.toCharArray()) out.append(' ').append(words(c - '0', code));
        return out.toString();
    }

    /** Every digit run in {@code text} as words: a model's stray digits must not reach Timbre. */
    public static String digitsToWords(String text, String code) {
        Matcher m = DIGITS.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String spoken;
            try {
                spoken = words(new BigDecimal(m.group().replace(",", "")), code);
            } catch (NumberFormatException e) {
                spoken = m.group();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(spoken));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** "चार नवंबर", "চার নভেম্বর", "the fourth of November, twenty twenty-six". */
    public static String date(LocalDate d, String code) {
        return switch (FixedLines.prefix(code)) {
            case "hi" -> words(d.getDayOfMonth(), code) + " " + HI_MONTHS[d.getMonthValue() - 1];
            case "bn" -> words(d.getDayOfMonth(), code) + " " + BN_MONTHS[d.getMonthValue() - 1];
            default -> SpokenEnglish.date(d);
        };
    }

    private static String kannada(long n) {
        if (n < 0) return "- " + kannada(-n);
        if (n < 20) return KN_UNITS[(int) n];
        if (n < 100) {
            String stem = KN_TENS[(int) (n / 10) - 2];
            int unit = (int) (n % 10);
            if (unit == 0) return stem + "ು";
            // Three and four join on the consonant; the others join as a vowel sign.
            return unit == 3 || unit == 4 ? stem.substring(0, stem.length() - 2) + "್" + KN_JOIN[unit] : stem + KN_JOIN[unit];
        }
        if (n < 1_000) {
            String full = KN_HUNDREDS[(int) (n / 100) - 1];
            long rest = n % 100;
            return rest == 0 ? full : full.substring(0, full.length() - 1) + " " + kannada(rest);
        }
        long[] scales = {1_00_00_000, 1_00_000, 1_000};
        String[] whole = {"ಕೋಟಿ", "ಲಕ್ಷ", "ಸಾವಿರ"};
        String[] joined = {"ಕೋಟಿ", "ಲಕ್ಷದ", "ಸಾವಿರದ"};
        for (int i = 0; i < scales.length; i++) {
            if (n >= scales[i]) {
                long rest = n % scales[i];
                String head = kannada(n / scales[i]) + " " + (rest == 0 ? whole[i] : joined[i]);
                return rest == 0 ? head : head + " " + kannada(rest);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private static String tamil(long n) {
        if (n < 0) return "- " + tamil(-n);
        if (n < 20) return TA_UNITS[(int) n];
        if (n < 100) {
            int tens = (int) (n / 10) - 2;
            return n % 10 == 0 ? TA_TENS[tens] : TA_TENS_JOIN[tens] + " " + TA_UNITS[(int) (n % 10)];
        }
        if (n < 1_000) {
            int hundreds = (int) (n / 100) - 1;
            return n % 100 == 0 ? TA_HUNDREDS[hundreds] : TA_HUNDREDS_JOIN[hundreds] + " " + tamil(n % 100);
        }
        long[] scales = {1_00_00_000, 1_00_000, 1_000};
        String[] whole = {"கோடி", "லட்சம்", "ஆயிரம்"};
        String[] joined = {"கோடியே", "லட்சத்து", "ஆயிரத்து"};
        for (int i = 0; i < scales.length; i++) {
            if (n >= scales[i]) {
                long count = n / scales[i];
                long rest = n % scales[i];
                String name = rest == 0 ? whole[i] : joined[i];
                String head = count == 1 && scales[i] == 1_000 ? name : tamil(count) + " " + name;
                return rest == 0 ? head : head + " " + tamil(rest);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private static String indic(long n, String[] small, String hundred, String thousand, String lakh, String crore,
                                String joinHundred) {
        if (n < 0) return "- " + indic(-n, small, hundred, thousand, lakh, crore, joinHundred);
        if (n < 100) return small[(int) n];
        if (n < 1_000) {
            String head = small[(int) (n / 100)] + hundred;
            return n % 100 == 0 ? head : head + " " + small[(int) (n % 100)];
        }
        long[] scales = {1_00_00_000, 1_00_000, 1_000};
        String[] names = {crore, lakh, thousand};
        for (int i = 0; i < scales.length; i++) {
            if (n >= scales[i]) {
                String head = indic(n / scales[i], small, hundred, thousand, lakh, crore, joinHundred) + " " + names[i];
                long rest = n % scales[i];
                return rest == 0 ? head : head + " " + indic(rest, small, hundred, thousand, lakh, crore, joinHundred);
            }
        }
        throw new IllegalStateException("unreachable");
    }
}
