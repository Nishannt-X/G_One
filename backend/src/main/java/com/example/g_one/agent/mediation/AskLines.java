package com.example.g_one.agent.mediation;

import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.agent.llm.SpokenNumbers;
import com.example.g_one.core.LegRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Every answer to a direct question, built from the fixed lines in {@code /prompts/fixed-lines.json} with the asker's
 * typed facts ({@link AskDigest.Facts}) filled in as words. Nothing here is model text, so an answer can only state a
 * fact the asker already has, can never predict what the other side will accept, and says a figure it worked out itself
 * is its own calculation. A missing line fails at startup.
 */
final class AskLines {
    static final List<String> KEYS = List.of("askQuantity", "askNoQuantity", "askPrice", "askPriceClosed",
            "askPriceAgreed", "askNoPrice", "askCrop", "askNoCrop", "askPickup", "askNoPickup", "askPayment",
            "askNoPayment", "askStateNone", "askStateOpen", "askStateConfirming", "askStateAgreed", "askRepeatFarmer",
            "askRepeatBuyer", "askNothingHeard", "askValue", "askPerKg", "askReference", "askNoReference",
            "askOnlyCall", "askSayDirectly", "askWho", "askAFarmer", "askNoLimits");
    private static final Map<String, Map<String, String>> BY_CODE = load();

    private AskLines() {}

    /** The buyer asking what the farmer would accept, their limits or for advice: refused, offering to translate a price. */
    static String noLimits(String code) {
        return line("askNoLimits", code);
    }

    /**
     * The answer to {@code intent}, in the asker's language.
     *
     * @param localize puts a short English deal phrase (a pickup or payment term) into the asker's language
     */
    static String render(AskGone.Intent intent, LegRole asker, AskDigest.Facts f, String code,
                         UnaryOperator<String> localize) {
        List<String> out = new ArrayList<>();
        switch (intent) {
            case QUANTITY -> out.add(quantity(f, code));
            case PRICE -> out.add(price(f, code));
            case CROP -> out.add(f.crop() == null ? line("askNoCrop", code)
                    : fill("askCrop", code, Map.of("crop", CropNames.spoken(f.crop(), f.variety(), code))));
            case PICKUP -> out.add(f.pickup() == null ? line("askNoPickup", code)
                    : fill("askPickup", code, Map.of("pickup", localize.apply(f.pickup()))));
            case PAYMENT -> out.add(f.payment() == null ? line("askNoPayment", code)
                    : fill("askPayment", code, Map.of("payment", localize.apply(f.payment()))));
            case STATUS -> {
                if (f.price() != null) out.add(price(f, code));
                out.add(state(f, code));
            }
            case LEFT -> {
                if (f.price() == null || f.priceState() == AskDigest.PriceState.CLOSED) out.add(price(f, code));
                if (f.quantity() == null) out.add(line("askNoQuantity", code));
                if (f.pickup() == null) out.add(line("askNoPickup", code));
                if (f.payment() == null) out.add(line("askNoPayment", code));
                out.add(state(f, code));
            }
            case REPEAT -> out.add(f.lastHeard() == null ? line("askNothingHeard", code)
                    : fill(asker == LegRole.BUYER ? "askRepeatFarmer" : "askRepeatBuyer", code, Map.of("text", f.lastHeard())));
            case WHO -> out.add(fill("askWho", code, Map.of("name",
                    f.otherName() != null ? f.otherName() : line("askAFarmer", code))));
            case VALUE -> {
                BigDecimal value = f.value();
                if ("total".equals(f.unit())) {
                    out.add(price(f, code));
                } else if (value == null) {
                    out.add(f.price() == null ? price(f, code) : quantity(f, code));
                } else {
                    out.add(fill("askValue", code, Map.of("quantity", CropNames.quantity(f.quantity(), code),
                            "price", SpokenNumbers.words(f.price(), code), "unit", CropNames.unit(f.unit(), code),
                            "value", SpokenNumbers.words(value, code))));
                }
            }
            case PER_KG -> {
                BigDecimal kg = f.perKg();
                out.add(kg == null ? price(f, code) : fill("askPerKg", code, Map.of("price",
                        SpokenNumbers.words(f.price(), code), "unit", CropNames.unit(f.unit(), code),
                        "perKg", SpokenNumbers.words(kg, code))));
            }
            case REFERENCE -> out.add(asker == LegRole.BUYER ? line("askOnlyCall", code) : reference(f, code));
            case ADVICE -> {
                if (asker == LegRole.BUYER) {
                    out.add(noLimits(code));
                } else {
                    // The farmer gets the facts to decide with, never a prediction of what the buyer will do.
                    out.add(price(f, code));
                    out.add(reference(f, code));
                }
            }
            case RELAY -> out.add(line("askSayDirectly", code));
            case SUMMARY -> summary(f, code, out);
            case OTHER -> {
                out.add(line("askOnlyCall", code));
                summary(f, code, out);
            }
        }
        return String.join(" ", out);
    }

    private static void summary(AskDigest.Facts f, String code, List<String> out) {
        out.add(quantity(f, code));
        out.add(price(f, code));
        out.add(state(f, code));
    }

    private static String quantity(AskDigest.Facts f, String code) {
        return f.quantity() == null ? line("askNoQuantity", code)
                : fill("askQuantity", code, Map.of("quantity", CropNames.quantity(f.quantity(), code)));
    }

    private static String price(AskDigest.Facts f, String code) {
        if (f.price() == null) return line("askNoPrice", code);
        if ("total".equals(f.unit())) {
            String amount = SpokenNumbers.words(f.price(), code);
            if (code.startsWith("hi")) return "पूरे माल का कुल भाव " + amount + " रुपये था।"
                    + (f.priceState() == AskDigest.PriceState.CLOSED ? " यह प्रस्ताव अब खुला नहीं है।" : "");
            if (code.startsWith("bn")) return "পুরো মালটির মোট দাম " + amount + " টাকা ছিল।"
                    + (f.priceState() == AskDigest.PriceState.CLOSED ? " প্রস্তাবটি আর খোলা নেই।" : "");
            return "The total price for all of it was " + amount + " rupees."
                    + (f.priceState() == AskDigest.PriceState.CLOSED ? " That offer is no longer open." : "");
        }
        String key = switch (f.priceState()) {
            case AGREED -> "askPriceAgreed";
            case CLOSED -> "askPriceClosed";
            default -> "askPrice";
        };
        return fill(key, code, Map.of("price", SpokenNumbers.words(f.price(), code), "unit", CropNames.unit(f.unit(), code)));
    }

    private static String state(AskDigest.Facts f, String code) {
        return switch (f.state()) {
            case "offer_open" -> line("askStateOpen", code);
            case "readback", "confirming" -> line("askStateConfirming", code);
            case "agreed" -> line("askStateAgreed", code);
            default -> line("askStateNone", code);
        };
    }

    private static String reference(AskDigest.Facts f, String code) {
        AskDigest.Reference r = f.reference();
        if (r == null) return line("askNoReference", code);
        return fill("askReference", code, Map.of("date", SpokenNumbers.date(r.date(), code), "source", r.source(),
                "market", r.market(), "crop", CropNames.spoken(r.crop(), r.variety(), code),
                "reference", SpokenNumbers.words(r.price(), code)));
    }

    private static String fill(String key, String code, Map<String, String> values) {
        String out = line(key, code);
        for (Map.Entry<String, String> v : values.entrySet()) out = out.replace("{" + v.getKey() + "}", v.getValue());
        return out;
    }

    static String line(String name, String code) {
        return BY_CODE.get(FixedLines.bestKey(BY_CODE.keySet(), code, FixedLines.DEFAULT_LANGUAGE)).get(name);
    }

    private static Map<String, Map<String, String>> load() {
        try (InputStream in = AskLines.class.getResourceAsStream(FixedLines.RESOURCE)) {
            if (in == null) throw new IllegalStateException("Fixed lines not found: " + FixedLines.RESOURCE);
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            new ObjectMapper().readTree(in).path("lines").fields().forEachRemaining(e -> {
                Map<String, String> lines = new LinkedHashMap<>();
                for (String k : KEYS) {
                    JsonNode v = e.getValue().path(k);
                    if (!v.isTextual() || v.asText().isBlank()) {
                        throw new IllegalStateException("Fixed line " + k + " is missing for " + e.getKey());
                    }
                    lines.put(k, v.asText());
                }
                out.put(e.getKey(), Map.copyOf(lines));
            });
            return Map.copyOf(out);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read fixed lines: " + FixedLines.RESOURCE, e);
        }
    }
}
