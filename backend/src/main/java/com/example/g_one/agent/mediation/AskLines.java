package com.example.g_one.agent.mediation;

import com.example.g_one.agent.deal.DealDesk;
import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.agent.llm.SpokenNumbers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed lines for a question to the assistant, from {@code /prompts/fixed-lines.json}: the answer when the model
 * fails, read from the deal so far, and the buyer's question about the farmer's limits. A missing line fails at startup.
 */
final class AskLines {
    static final List<String> KEYS = List.of("askQuantity", "askNoQuantity", "askPrice", "askNoPrice", "askNoLimits");
    private static final Map<String, Map<String, String>> BY_CODE = load();

    private AskLines() {}

    /** "No limits shared; propose a price and I will pass it on", in the asker's language. */
    static String noLimits(String code) {
        return line("askNoLimits", code);
    }

    /** What the deal card says about quantity and price, with numbers as words. Never invents a figure. */
    static String fromCard(DealDesk.Card card, String code) {
        String quantity = card.quantity() == null ? line("askNoQuantity", code)
                : line("askQuantity", code).replace("{quantity}", CropNames.quantity(card.quantity(), code));
        String price = !card.hasPrice() ? line("askNoPrice", code)
                : line("askPrice", code).replace("{price}", SpokenNumbers.words(card.price(), code))
                        .replace("{unit}", CropNames.unit(card.unit(), code));
        return quantity + " " + price;
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
