package com.example.g_one.agent.tools.buyers;

import com.example.g_one.agent.llm.SpokenEnglish;
import com.example.g_one.agent.tools.ToolArgs;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code find_buyers(commodity, quantity?, district?)}: the top three directory buyers for the crop, each with a short
 * spoken reason and numbers as words. Never carries a phone number or {@code phoneRef}.
 */
@Component
public class FindBuyersTool implements Tool {
    public static final String NAME = "find_buyers";
    public static final int TOP = 3;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode SCHEMA = ToolArgs.schema("""
            {"type": "object",
             "properties": {
               "commodity": {"type": "string", "description": "The crop as the farmer said it, for example onion, pyaz or प्याज"},
               "quantity": {"type": "number", "description": "How many quintals the farmer wants to sell, if said"},
               "district": {"type": "string", "description": "The farmer's district, if known"}},
             "required": ["commodity"]}""");

    private final BuyerRanker ranker;
    private final BuyerConsent consent;

    @Autowired
    public FindBuyersTool(BuyerRanker ranker, BuyerConsent consent) {
        this.ranker = ranker;
        this.consent = consent;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Find buyers in the directory for the farmer's crop, best match first. Call it when the farmer wants to "
                + "sell. Suggest at most two to the farmer and recommend one.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        Optional<String> commodity = ToolArgs.text(args, "commodity");
        if (commodity.isEmpty()) return ToolResult.error("commodity is required.");
        Double quantity = quantity(args);
        String district = ToolArgs.text(args, "district").orElse(null);
        List<BuyerRanker.Match> top = ranker.rank(commodity.get(), quantity, district).stream().limit(TOP).toList();
        if (top.isEmpty()) {
            return ToolResult.ok("The directory has no buyer for " + commodity.get() + ". Tell the farmer plainly.");
        }
        consent.suggested(ctx.callId(), top.stream().map(m -> m.buyer().id()).toList(), commodity.get(),
                quantity == null ? null : wholeOrPlain(quantity) + " quintal", district);

        ObjectNode out = JSON.createObjectNode();
        ArrayNode buyers = out.putArray("buyers");
        for (BuyerRanker.Match m : top) buyers.add(view(m));
        out.put("instructions", "Suggest at most two and recommend the first. Say the range is what the directory "
                + "lists, not a quote. Then ask one yes or no question: shall I call them now?");
        return ToolResult.ok(out.toString());
    }

    private static ObjectNode view(BuyerRanker.Match m) {
        Buyer b = m.buyer();
        Buyer.Crop c = m.crop();
        ObjectNode n = JSON.createObjectNode();
        n.put("buyerId", b.id());
        n.put("name", b.name());
        n.put("type", b.type());
        n.put("place", b.district());
        n.put("language", b.language());
        n.put("listedRange", SpokenEnglish.number(c.minPrice()) + " to " + SpokenEnglish.number(c.maxPrice())
                + " rupees per " + c.unit());
        n.put("reason", reason(m));
        return n;
    }

    /** "Buys onion, collects from your district, about two hundred kilometres away, rated ninety-two out of hundred." */
    static String reason(BuyerRanker.Match m) {
        StringBuilder s = new StringBuilder("Buys ").append(m.crop().crop().toLowerCase(Locale.ROOT));
        if (m.servesDistrict()) s.append(", collects from your district");
        if (m.distanceKm() != null) {
            s.append(", about ").append(SpokenEnglish.number(m.distanceKm())).append(" kilometres away");
        }
        s.append(", rated ").append(SpokenEnglish.number(m.buyer().reliability())).append(" out of hundred in the directory");
        return s.append('.').toString();
    }

    private static Double quantity(JsonNode args) {
        JsonNode q = args.get("quantity");
        if (q == null || q.isNull()) return null;
        double v;
        if (q.isNumber()) {
            v = q.asDouble();
        } else if (q.isTextual()) {
            try {
                v = Double.parseDouble(q.asText().replaceAll("[^0-9.]", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        return v > 0 ? v : null;
    }

    private static String wholeOrPlain(double q) {
        return q == Math.rint(q) ? Long.toString((long) q) : Double.toString(q);
    }
}
