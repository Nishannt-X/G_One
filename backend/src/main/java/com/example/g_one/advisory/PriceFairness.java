package com.example.g_one.advisory;

import com.example.g_one.agent.llm.SpokenNumbers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluates a quoted crop price against bundled indicative sample mandi modal prices and MSP benchmarks.
 * Categorizes the offer into GREEN, AMBER, or RED, computes the gap in ₹/quintal, and produces bilingual whisper text
 * with Hindi numbers spelled out as spoken words.
 */
public class PriceFairness {

    public enum Status {
        GREEN,
        AMBER,
        RED,
        UNKNOWN
    }

    public record CropRecord(
            String name,
            String hindi,
            int modal,
            Integer msp,
            List<String> aliases
    ) {}

    public record PriceEvaluation(
            boolean found,
            String commodity,
            String hindiName,
            double quotedPrice,
            int modalPrice,
            Integer msp,
            int benchmarkPrice,
            double gapRupeesPerQuintal,
            Status status,
            String whisperTextEn,
            String whisperTextHi
    ) {
        public static PriceEvaluation notFound(String commodity, double quotedPrice) {
            String en = "No indicative sample price found for crop '" + commodity + "'.";
            String hi = "फसल '" + commodity + "' के लिए कोई सांकेतिक नमूना भाव नहीं मिला।";
            return new PriceEvaluation(
                    false, commodity, commodity, quotedPrice, 0, null, 0, 0.0,
                    Status.UNKNOWN, en, hi
            );
        }
    }

    private final List<CropRecord> crops = new ArrayList<>();
    private final Map<String, CropRecord> aliasMap = new HashMap<>();

    public PriceFairness() {
        this("/advisory/prices.json");
    }

    public PriceFairness(String resourcePath) {
        loadPrices(resourcePath);
    }

    private void loadPrices(String resourcePath) {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                // Fallback to price-book.sample.json if prices.json not on classpath
                try (InputStream fallbackIn = getClass().getResourceAsStream("/advisory/price-book.sample.json")) {
                    if (fallbackIn != null) {
                        parse(mapper.readTree(fallbackIn));
                    }
                }
                return;
            }
            parse(mapper.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load indicative sample prices from " + resourcePath, e);
        }
    }

    private void parse(JsonNode root) {
        JsonNode cropsNode = root.path("crops");
        if (!cropsNode.isArray()) return;
        for (JsonNode c : cropsNode) {
            String name = c.path("name").asText();
            String hindi = c.path("hindi").asText();
            int modal = c.path("modal").asInt();
            Integer msp = c.hasNonNull("msp") ? c.get("msp").asInt() : null;
            List<String> aliases = new ArrayList<>();
            aliases.add(name.toLowerCase(Locale.ROOT));
            aliases.add(hindi.toLowerCase(Locale.ROOT));
            if (c.has("aliases") && c.get("aliases").isArray()) {
                for (JsonNode a : c.get("aliases")) {
                    aliases.add(a.asText().trim().toLowerCase(Locale.ROOT));
                }
            }
            CropRecord rec = new CropRecord(name, hindi, modal, msp, Collections.unmodifiableList(aliases));
            crops.add(rec);
            for (String alias : aliases) {
                aliasMap.putIfAbsent(alias, rec);
            }
        }
    }

    public List<CropRecord> allCrops() {
        return Collections.unmodifiableList(crops);
    }

    public Optional<CropRecord> findCrop(String rawName) {
        if (rawName == null || rawName.isBlank()) return Optional.empty();
        String normalized = rawName.trim().toLowerCase(Locale.ROOT);
        CropRecord match = aliasMap.get(normalized);
        if (match != null) return Optional.of(match);

        // Substring / partial matching
        for (CropRecord c : crops) {
            for (String a : c.aliases()) {
                if (normalized.contains(a) || a.contains(normalized)) {
                    return Optional.of(c);
                }
            }
        }
        return Optional.empty();
    }

    public PriceEvaluation evaluate(String rawCommodity, double quotedPrice) {
        Optional<CropRecord> match = findCrop(rawCommodity);
        if (match.isEmpty()) {
            return PriceEvaluation.notFound(rawCommodity, quotedPrice);
        }
        CropRecord crop = match.get();
        int benchmark = crop.modal();
        // If MSP is higher than modal, benchmark can take MSP into account
        int referenceBenchmark = (crop.msp() != null && crop.msp() > crop.modal()) ? crop.msp() : crop.modal();
        double gap = quotedPrice - benchmark;

        Status status;
        if (gap >= 0) {
            status = Status.GREEN;
        } else if (quotedPrice >= benchmark * 0.90) { // within 10% below benchmark
            status = Status.AMBER;
        } else {
            status = Status.RED;
        }

        String whisperEn = buildWhisperEn(crop, quotedPrice, benchmark, gap, status);
        String whisperHi = buildWhisperHi(crop, quotedPrice, benchmark, gap, status);

        return new PriceEvaluation(
                true,
                crop.name(),
                crop.hindi(),
                quotedPrice,
                crop.modal(),
                crop.msp(),
                referenceBenchmark,
                gap,
                status,
                whisperEn,
                whisperHi
        );
    }

    private String buildWhisperEn(CropRecord crop, double quotedPrice, int benchmark, double gap, Status status) {
        long quotedRound = Math.round(quotedPrice);
        long gapAbs = Math.abs(Math.round(gap));
        String mspNote = crop.msp() != null ? " (indicative sample MSP: ₹" + crop.msp() + "/quintal)" : "";

        return switch (status) {
            case GREEN -> crop.name() + ": Favorable offer. Quoted price of ₹" + quotedRound + "/quintal is ₹" + gapAbs
                    + "/quintal above the indicative sample mandi modal price of ₹" + benchmark + "/quintal" + mspNote + ".";
            case AMBER -> crop.name() + ": Negotiable offer. Quoted price of ₹" + quotedRound + "/quintal is ₹" + gapAbs
                    + "/quintal below the indicative sample mandi modal price of ₹" + benchmark + "/quintal" + mspNote + ".";
            case RED -> crop.name() + ": Low offer. Quoted price of ₹" + quotedRound + "/quintal is ₹" + gapAbs
                    + "/quintal below the indicative sample mandi modal price of ₹" + benchmark + "/quintal" + mspNote + ".";
            default -> crop.name() + ": Quoted price is ₹" + quotedRound + "/quintal.";
        };
    }

    private String buildWhisperHi(CropRecord crop, double quotedPrice, int benchmark, double gap, Status status) {
        long quotedRound = Math.round(quotedPrice);
        long gapAbs = Math.abs(Math.round(gap));
        String quotedWords = SpokenNumbers.words(quotedRound, "hi-IN");
        String gapWords = SpokenNumbers.words(gapAbs, "hi-IN");
        String benchmarkWords = SpokenNumbers.words(benchmark, "hi-IN");

        String mspNote = "";
        if (crop.msp() != null) {
            String mspWords = SpokenNumbers.words(crop.msp(), "hi-IN");
            mspNote = " (सांकेतिक नमूना एमएसपी " + mspWords + " रुपये प्रति क्विंटल है)";
        }

        return switch (status) {
            case GREEN -> crop.hindi() + " के लिए अच्छा भाव है। दिया गया भाव " + quotedWords + " रुपये प्रति क्विंटल, सांकेतिक नमूना मंडी भाव "
                    + benchmarkWords + " रुपये से " + gapWords + " रुपये प्रति क्विंटल अधिक है" + mspNote + "।";
            case AMBER -> crop.hindi() + " पर बातचीत की गुंजाइश है। दिया गया भाव " + quotedWords + " रुपये प्रति क्विंटल, सांकेतिक नमूना मंडी भाव "
                    + benchmarkWords + " रुपये से " + gapWords + " रुपये प्रति क्विंटल कम है" + mspNote + "।";
            case RED -> crop.hindi() + " का भाव कम है। दिया गया भाव " + quotedWords + " रुपये प्रति क्विंटल, सांकेतिक नमूना मंडी भाव "
                    + benchmarkWords + " रुपये से " + gapWords + " रुपये प्रति क्विंटल कम है" + mspNote + "।";
            default -> crop.hindi() + " का दिया गया भाव " + quotedWords + " रुपये प्रति क्विंटल है।";
        };
    }
}
