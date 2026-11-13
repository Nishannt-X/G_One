package com.example.g_one.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deal card arithmetic for a stored record, worked out like the console's: the price basis, the total the terms add up
 * to, and the payment part of the combined pickup field. Only the deal's own terms go in; nothing is guessed.
 */
final class DealTotal {
    private static final Map<String, BigDecimal> UNIT_KG = Map.ofEntries(
            Map.entry("kg", BigDecimal.ONE), Map.entry("kgs", BigDecimal.ONE), Map.entry("kilo", BigDecimal.ONE),
            Map.entry("kilos", BigDecimal.ONE), Map.entry("kilogram", BigDecimal.ONE),
            Map.entry("kilograms", BigDecimal.ONE), Map.entry("quintal", BigDecimal.valueOf(100)),
            Map.entry("quintals", BigDecimal.valueOf(100)), Map.entry("qtl", BigDecimal.valueOf(100)),
            Map.entry("ton", BigDecimal.valueOf(1000)), Map.entry("tons", BigDecimal.valueOf(1000)),
            Map.entry("tonne", BigDecimal.valueOf(1000)), Map.entry("tonnes", BigDecimal.valueOf(1000)));
    private static final Map<String, Integer> SMALL = Map.ofEntries(
            Map.entry("zero", 0), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9),
            Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("thirteen", 13),
            Map.entry("fourteen", 14), Map.entry("fifteen", 15), Map.entry("sixteen", 16), Map.entry("seventeen", 17),
            Map.entry("eighteen", 18), Map.entry("nineteen", 19), Map.entry("twenty", 20), Map.entry("thirty", 30),
            Map.entry("forty", 40), Map.entry("fifty", 50), Map.entry("sixty", 60), Map.entry("seventy", 70),
            Map.entry("eighty", 80), Map.entry("ninety", 90));
    private static final Map<String, Integer> LARGE = Map.of("thousand", 1_000, "lakh", 100_000, "lakhs", 100_000,
            "crore", 10_000_000);
    private static final Pattern QUANTITY = Pattern.compile("^\\s*(.+?)\\s*(kilograms|kilogram|quintals|quintal|tonnes"
            + "|tonne|kilos|kilo|tons|ton|kgs|kg|qtl)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGITS = Pattern.compile("[\\d,]+(?:\\.\\d+)?");
    /** The combined pickup field as the deal desk writes it: "Monday at the farm; payment on delivery". */
    private static final Pattern PAYMENT = Pattern.compile("(?:^|;\\s*)payment\\s+(.{1,80})$");

    private DealTotal() {}

    /** "per quintal", "total", or null when there is no unit. */
    static String basis(String unit) {
        if (unit == null || unit.isBlank()) return null;
        return "total".equals(unit) ? "total" : "per " + unit;
    }

    /** What the terms add up to in rupees, or null unless the quantity and the price basis can both be read. */
    static BigDecimal of(String quantity, BigDecimal price, String unit) {
        if (price == null || unit == null) return null;
        if ("total".equals(unit)) return plain(price);
        BigDecimal kg = quantityKg(quantity);
        BigDecimal unitKg = UNIT_KG.get(unit.toLowerCase(Locale.ROOT));
        if (kg == null || unitKg == null) return null;
        return plain(kg.multiply(price).divide(unitKg, 2, RoundingMode.HALF_UP));
    }

    /** The payment terms inside the combined pickup field, or null when none were stated. */
    static String payment(String pickup) {
        if (pickup == null) return null;
        Matcher m = PAYMENT.matcher(pickup);
        return m.find() ? m.group(1).strip() : null;
    }

    /** "10 quintal" or "ten quintals" in kilograms, or null. */
    static BigDecimal quantityKg(String quantity) {
        if (quantity == null) return null;
        Matcher m = QUANTITY.matcher(quantity);
        if (!m.matches()) return null;
        String amount = m.group(1);
        BigDecimal n;
        if (DIGITS.matcher(amount).matches()) {
            try {
                n = new BigDecimal(amount.replace(",", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            Long words = words(amount);
            if (words == null) return null;
            n = BigDecimal.valueOf(words);
        }
        return n.multiply(UNIT_KG.get(m.group(2).toLowerCase(Locale.ROOT)));
    }

    /** "four thousand five hundred" as 4500, or null when the words are not a number. */
    private static Long words(String text) {
        long total = 0;
        long current = 0;
        boolean any = false;
        for (String w : text.toLowerCase(Locale.ROOT).split("[\\s-]+")) {
            if (w.isEmpty() || w.equals("and")) continue;
            any = true;
            if (SMALL.containsKey(w)) current += SMALL.get(w);
            else if (w.equals("hundred")) current = (current == 0 ? 1 : current) * 100;
            else if (LARGE.containsKey(w)) {
                total += (current == 0 ? 1 : current) * LARGE.get(w);
                current = 0;
            } else return null;
        }
        return any ? total + current : null;
    }

    /** 21000 rather than 2.1E+4, and no trailing zeros. */
    private static BigDecimal plain(BigDecimal v) {
        return new BigDecimal(v.stripTrailingZeros().toPlainString());
    }
}
