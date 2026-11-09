package com.example.g_one.advisory;

import com.example.g_one.agent.tools.ToolArgs;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Core Tool wrapper for {@link DeductionShield}. Analyzes mandi weight cuts and fees to provide net price and counter-offer.
 */
@Component
public class DeductionShieldTool implements Tool {
    public static final String NAME = "deduction_shield";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode SCHEMA = ToolArgs.schema("""
            {"type": "object",
             "properties": {
               "basePrice": {"type": "number", "description": "The base or gross price offered in rupees per quintal"},
               "terms": {"type": "string", "description": "The buyer's proposed deductions, for example '5 kg per bag cut, 2% commission, 20 rupay palledari'"}
             },
             "required": ["basePrice", "terms"]}""");

    private final DeductionShield shield = new DeductionShield();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Parse mandi deductions (weight cut, kata, dhalta, moisture cut, commission, palledari) and calculate the net price per quintal with a counter-offer recommendation.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        if (!args.has("basePrice") || !args.get("basePrice").isNumber()) {
            return ToolResult.error("basePrice (number) is required.");
        }
        Optional<String> terms = ToolArgs.text(args, "terms");
        if (terms.isEmpty()) {
            return ToolResult.error("terms (string) is required.");
        }

        double basePrice = args.get("basePrice").asDouble();
        DeductionShield.Calculation calc = shield.analyze(basePrice, terms.get());

        ObjectNode out = JSON.createObjectNode();
        out.put("basePrice", calc.basePriceRupeesPerQuintal());
        out.put("netPrice", calc.netPriceRupeesPerQuintal());
        out.put("totalDeductions", calc.totalDeductionsRupeesPerQuintal());
        out.put("totalWeightCutKg", calc.totalWeightCutKgPerQuintal());
        out.put("suggestedCounterOffer", calc.suggestedCounterOfferRupeesPerQuintal());
        out.put("suggestionEn", calc.suggestionEn());
        out.put("suggestionHi", calc.suggestionHi());

        ArrayNode items = out.putArray("items");
        for (DeductionShield.DeductionItem item : calc.items()) {
            ObjectNode o = items.addObject();
            o.put("name", item.name());
            o.put("hindiName", item.hindiName());
            o.put("value", item.value());
            o.put("unit", item.unit());
            o.put("rupeesPerQuintal", item.rupeesPerQuintal());
            o.put("rawMatch", item.rawMatch());
        }

        return ToolResult.ok(out.toString());
    }
}
