package com.example.g_one.advisory;

import com.example.g_one.agent.tools.ToolArgs;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Core Tool wrapper for {@link PriceFairness}. Compares quoted crop price against indicative sample mandi modal and MSP.
 */
@Component
public class PriceFairnessTool implements Tool {
    public static final String NAME = "price_fairness";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode SCHEMA = ToolArgs.schema("""
            {"type": "object",
             "properties": {
               "commodity": {"type": "string", "description": "The crop name, for example onion, wheat, gehun, pyaz or धान"},
               "price": {"type": "number", "description": "The offered or quoted price in rupees per quintal"}
             },
             "required": ["commodity", "price"]}""");

    private final PriceFairness fairness = new PriceFairness();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Check if a quoted price is fair by comparing it against bundled indicative sample mandi modal prices and government MSP benchmarks.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        Optional<String> commodity = ToolArgs.text(args, "commodity");
        if (commodity.isEmpty() || commodity.get().isBlank()) {
            return ToolResult.error("commodity is required.");
        }
        if (!args.has("price") || !args.get("price").isNumber()) {
            return ToolResult.error("price (number) is required.");
        }
        double price = args.get("price").asDouble();

        PriceFairness.PriceEvaluation eval = fairness.evaluate(commodity.get(), price);
        ObjectNode out = JSON.createObjectNode();
        out.put("found", eval.found());
        out.put("commodity", eval.commodity());
        out.put("hindiName", eval.hindiName());
        out.put("quotedPrice", eval.quotedPrice());
        out.put("modalPrice", eval.modalPrice());
        if (eval.msp() != null) {
            out.put("msp", eval.msp());
        } else {
            out.putNull("msp");
        }
        out.put("benchmarkPrice", eval.benchmarkPrice());
        out.put("gapRupeesPerQuintal", eval.gapRupeesPerQuintal());
        out.put("status", eval.status().name());
        out.put("whisperTextEn", eval.whisperTextEn());
        out.put("whisperTextHi", eval.whisperTextHi());

        return ToolResult.ok(out.toString());
    }
}
