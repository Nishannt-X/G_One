package com.example.g_one.agent.mediation;

import com.example.g_one.agent.deal.Offer;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps only the terms of a model's reading that the words really carry. The reading is the model's, not evidence of
 * what was spoken, so a term counts for a party only when the text they said or heard holds it: the figure (digits or
 * number words, read by {@link NumberGuard}) and its unit word, in that text's language. A crop, variety or market name
 * counts only when its word is in the text. Anything else is
 * dropped for that party, who is then told it has not been mentioned. Pickup and payment phrases count only when every
 * word of the phrase is in the text; otherwise the party learns them from the read-back, whose words are built from
 * the terms themselves.
 */
final class HeardTerms {
    /** Unit words in every language a line can be spoken in. */
    private static final Map<String, List<String>> UNITS = Map.of(
            "quintal", List.of("quintal", "qtl", "क्विंटल", "कुंतल", "কুইন্টাল", "குவிண்டால்", "ಕ್ವಿಂಟಲ್", "ಕ್ವಿಂಟಾಲ್"),
            "kg", List.of("kg", "kilo", "किलो", "केजी", "কেজি", "কিলো", "கிலோ", "ಕೆಜಿ", "ಕಿಲೋ"),
            "ton", List.of("ton", "टन", "টন", "டன்", "ಟನ್"),
            "tonne", List.of("ton", "टन", "টন", "டன்", "ಟನ್"),
            "total", List.of("total", "पूरे", "कुल", "মোট", "மொத்தம்", "ಒಟ್ಟು"));
    private static final Pattern AMOUNT = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*([\\p{L}]*)");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]{3,}");

    private HeardTerms() {}

    /**
     * The terms of {@code offer} that {@code text} (in {@code code}) actually carries; the rest are null. Null when
     * nothing is left.
     */
    static Offer in(Offer offer, String text, String code) {
        if (offer == null || text == null || text.isBlank()) return null;
        String t = text.toLowerCase(Locale.ROOT);
        boolean price = offer.price() != null && figure(offer.price().toPlainString(), text, code) && unit(offer.unit(), t);
        String quantity = quantity(offer.quantity(), text, t, code) ? offer.quantity() : null;
        String commodity = crop(offer.commodity(), t) ? offer.commodity() : null;
        String variety = crop(offer.variety(), t) ? offer.variety() : null;
        String market = words(offer.market(), t) ? offer.market() : null;
        String pickup = words(offer.pickup(), t) ? offer.pickup() : null;
        String payment = words(offer.payment(), t) ? offer.payment() : null;
        // A clear offer needs its figure and unit heard. The crop is usually not repeated; it stays as already known.
        boolean clear = "clear".equals(offer.status()) && price;
        Offer kept = new Offer(clear ? "clear" : "none", commodity, variety, market, clear ? offer.price() : null,
                offer.currency(), clear ? offer.unit() : null, quantity, pickup, payment);
        return kept.price() == null && kept.commodity() == null && !kept.hasDetails() ? null : kept;
    }

    private static boolean quantity(String quantity, String text, String lower, String code) {
        if (quantity == null) return false;
        Matcher m = AMOUNT.matcher(quantity);
        if (!m.find()) return words(quantity, lower);
        return figure(m.group(1), text, code) && (m.group(2).isEmpty() || unit(m.group(2), lower));
    }

    /** The number is among the numbers {@code text} says, in digits or words. */
    private static boolean figure(String number, String text, String code) {
        BigDecimal n;
        try {
            n = new BigDecimal(number);
        } catch (NumberFormatException e) {
            return false;
        }
        if (n.signum() <= 0) return false;
        // A plain figure has a strong reading of its own; the guard reports it missing when the text lacks it.
        return NumberGuard.check(n.stripTrailingZeros().toPlainString() + " quintal", "en-IN", text, code).missing().isEmpty();
    }

    private static boolean unit(String unit, String lower) {
        if (unit == null) return false;
        List<String> words = UNITS.get(Offer.unit(unit));
        return words != null && words.stream().anyMatch(lower::contains);
    }

    /** The crop or variety word, in English or as spoken in Hindi or Bengali. */
    private static boolean crop(String name, String lower) {
        if (name == null) return false;
        String key = name.toLowerCase(Locale.ROOT).strip();
        String singular = key.endsWith("s") ? key.substring(0, key.length() - 1) : key;
        return lower.contains(singular) || lower.contains(CropNames.spoken(singular, null, "hi-IN").toLowerCase(Locale.ROOT))
                || lower.contains(CropNames.spoken(singular, null, "bn-IN").toLowerCase(Locale.ROOT));
    }

    /** Every word of the phrase is in the text. */
    private static boolean words(String phrase, String lower) {
        if (phrase == null) return false;
        Matcher m = WORD.matcher(phrase.toLowerCase(Locale.ROOT));
        boolean any = false;
        while (m.find()) {
            any = true;
            if (!lower.contains(m.group())) return false;
        }
        return any;
    }
}
