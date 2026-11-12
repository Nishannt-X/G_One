package com.example.g_one.agent.mediation;

import java.util.Locale;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Reads explicit basis words from the speaker, independently of the model's extraction. */
final class PriceBasis {
    private static final String TOTAL = "(?:total|in all|for everything|for all(?: of it)?|lump sum|sab ka|poore ka|pure ka|ek saath|कुल|पूरे का|सबका|মোট|மொத்தம்|ಒಟ್ಟು)";
    private static final String UNIT = "(?:quintal|qtl|क्विंटल|কুইন্টাল|குவிண்டால்|ಕ್ವಿಂಟಲ್|kilogram|kilo|kg|किलो|केजी|কিলো|কেজি|கிலோ|ಕಿಲೋ|ಕೆಜಿ|tonne|ton|टन|টন|டன்|ಟನ್|piece|dozen|bag|bori)";
    private static final String PER = "(?:per|each|a|an|prati|har|प्रति|हर|প্রতি|ஒவ்வொரு|ಪ್ರತಿ)";
    private static final Pattern TOTAL_RE = Pattern.compile("(?iu)(?<![\\p{L}])" + TOTAL + "(?![\\p{L}])");
    private static final Pattern RATE_RE = Pattern.compile("(?iu)(?<![\\p{L}])" + PER + "\\s*(" + UNIT + ")(?![\\p{L}])");
    private static final Pattern CONDITIONAL = Pattern.compile("(?iu)(?<![\\p{L}])(?:if|unless|only|except|not|no|agar|sirf|nahi|nahin|mat|अगर|यदि|सिर्फ|नहीं|मत|যদি|শুধু|না|என்றால்|மட்டும்|ಇದ್ದರೆ|ಮಾತ್ರ)(?![\\p{L}])");

    private PriceBasis() {}

    static boolean conditional(String text) {
        return text != null && (text.contains("?") || text.contains("？") || CONDITIONAL.matcher(text).find());
    }

    static boolean conflicting(String text) {
        if (text == null) return false;
        var rates = RATE_RE.matcher(text);
        String first = null;
        while (rates.find()) {
            String unit = canonical(rates.group(1));
            if (first != null && !first.equals(unit)) return true;
            first = unit;
        }
        return first != null && TOTAL_RE.matcher(text).find();
    }

    static String read(String text) {
        if (text == null) return null;
        boolean total = TOTAL_RE.matcher(text).find();
        var matcher = RATE_RE.matcher(text);
        String rate = null;
        while (matcher.find()) {
            String unit = canonical(matcher.group(1));
            if (rate != null && !rate.equals(unit)) return null;
            rate = unit;
        }
        return total && rate != null ? null : total ? "total" : rate;
    }

    /** Tie the basis to the price, rather than to another number in the same turn. */
    static String evidence(String text, String code, BigDecimal price) {
        String basis = read(text);
        if (basis == null || price == null) return null;
        String number = price.stripTrailingZeros().toPlainString();
        var numbers = NumberGuard.numbers(text, code);
        if (!numbers.contains(number)) return null;
        String digitized = NumberGuard.digitized(text, code);
        String amount = "(?<![\\d.])" + Pattern.quote(number) + "(?![\\d.])";
        String money = "(?:\\s*(?:rupees?|rs|inr|रुपये|रुपए|টাকা|ರೂಪಾಯಿ|ரூபாய்))?\\s*";
        String tie = "total".equals(basis) ? "(?:" + amount + money + TOTAL + "|" + TOTAL + "\\s*(?:of\\s*)?" + amount + ")"
                : "(?:" + amount + money + PER + "\\s*" + UNIT + "|"
                    + PER + "\\s*" + UNIT + "\\s+" + amount + ")";
        return Pattern.compile("(?iu)" + tie).matcher(digitized).find() ? basis : null;
    }

    static String clarification(String text) {
        if (text == null) return null;
        if (conditional(text)) return null;
        String t = text.strip().replaceAll("[.!?।]+$", "").strip();
        if (TOTAL_RE.matcher(t).matches()) return "total";
        var matcher = RATE_RE.matcher(t);
        return matcher.matches() ? canonical(matcher.group(1)) : null;
    }

    private static String canonical(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.matches("(?iu)kg|kilo|kilogram|किलो|केजी|কিলো|কেজি|கிலோ|ಕಿಲೋ|ಕೆಜಿ")) return "kg";
        if (t.matches("(?iu)quintal|qtl|क्विंटल|কুইন্টাল|குவிண்டால்|ಕ್ವಿಂಟಲ್")) return "quintal";
        if (t.matches("(?iu)tonne|ton|टन|টন|டன்|ಟನ್")) return "tonne";
        return t.equals("bori") ? "bag" : t;
    }
}
