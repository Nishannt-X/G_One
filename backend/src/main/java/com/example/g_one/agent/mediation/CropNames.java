package com.example.g_one.agent.mediation;

import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.agent.llm.SpokenNumbers;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Crop, variety and unit names as spoken in Hindi and Bengali. A name not listed is spoken as it was written. */
public final class CropNames {
    private static final Map<String, Map<String, String>> WORDS = Map.of(
            "hi", Map.ofEntries(Map.entry("onion", "प्याज़"), Map.entry("rice", "चावल"), Map.entry("paddy", "धान"),
                    Map.entry("potato", "आलू"), Map.entry("tomato", "टमाटर"), Map.entry("wheat", "गेहूँ"),
                    Map.entry("soyabean", "सोयाबीन"), Map.entry("cotton", "कपास"), Map.entry("red", "लाल"),
                    Map.entry("quintal", "क्विंटल"), Map.entry("kg", "किलो"), Map.entry("ton", "टन")),
            "bn", Map.ofEntries(Map.entry("onion", "পেঁয়াজ"), Map.entry("rice", "চাল"), Map.entry("paddy", "ধান"),
                    Map.entry("potato", "আলু"), Map.entry("tomato", "টমেটো"), Map.entry("wheat", "গম"),
                    Map.entry("soyabean", "সয়াবিন"), Map.entry("cotton", "তুলা"), Map.entry("red", "লাল"),
                    Map.entry("quintal", "কুইন্টাল"), Map.entry("kg", "কেজি"), Map.entry("ton", "টন")));
    private static final Pattern QUANTITY = Pattern.compile("^\\s*([\\d.,]+)\\s*(.*)$");

    private CropNames() {}

    /** "लाल प्याज़", "red onion". */
    public static String spoken(String commodity, String variety, String code) {
        String crop = word(commodity, code);
        return variety == null || variety.isBlank() ? crop : word(variety, code) + " " + crop;
    }

    public static String unit(String unit, String code) {
        return word(unit, code);
    }

    /** "20 quintal" as "बीस क्विंटल". Text that does not start with a number is returned as it is. */
    static String quantity(String quantity, String code) {
        Matcher m = QUANTITY.matcher(quantity);
        if (!m.matches()) return SpokenNumbers.digitsToWords(quantity, code);
        String number = SpokenNumbers.digitsToWords(m.group(1), code);
        String unit = m.group(2).isBlank() ? "" : " " + word(m.group(2).strip(), code);
        return number + unit;
    }

    private static String word(String word, String code) {
        Map<String, String> table = WORDS.get(FixedLines.prefix(code));
        if (table == null) return word.toLowerCase(Locale.ROOT);
        String key = word.toLowerCase(Locale.ROOT).strip();
        if (key.endsWith("s") && table.containsKey(key.substring(0, key.length() - 1))) key = key.substring(0, key.length() - 1);
        return table.getOrDefault(key, word);
    }
}
