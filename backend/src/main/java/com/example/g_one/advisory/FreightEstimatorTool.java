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
 * Core Tool wrapper for {@link FreightEstimator}. Estimates road freight and compares farm-gate vs mandi net.
 */
@Component
public class FreightEstimatorTool implements Tool {
    public static final String NAME = "estimate_freight";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode SCHEMA = ToolArgs.schema("""
            {"type": "object",
             "properties": {
               "from": {"type": "string", "description": "Origin town or village, for example Niphad, Memari or Dewas"},
               "to": {"type": "string", "description": "Destination mandi, for example Lasalgaon, Pimpalgaon, Vashi or Azadpur"},
               "distanceKm": {"type": "integer", "description": "Manual road distance in km if known or unmapped"},
               "vehicle": {"type": "string", "description": "Vehicle type, e.g. pickup, mini_truck, chhota hathi, tractor or truck"},
               "quantity": {"type": "number", "description": "Quantity to transport in quintals"},
               "farmGatePrice": {"type": "number", "description": "Price offered by buyer at farm-gate (rupees/quintal)"},
               "mandiPrice": {"type": "number", "description": "Prevailing mandi price (rupees/quintal)"}
             },
             "required": ["from", "to"]}""");

    private final FreightEstimator estimator = new FreightEstimator();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Estimate road freight cost per quintal based on synthetic route tables and vehicle types, and compare farm-gate offers against mandi net earnings.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        Optional<String> from = ToolArgs.text(args, "from");
        Optional<String> to = ToolArgs.text(args, "to");
        if (from.isEmpty() || from.get().isBlank()) {
            return ToolResult.error("from is required.");
        }
        if (to.isEmpty() || to.get().isBlank()) {
            return ToolResult.error("to is required.");
        }

        Integer distanceKm = args.has("distanceKm") && args.get("distanceKm").isInt() ? args.get("distanceKm").asInt() : null;
        String vehicle = ToolArgs.text(args, "vehicle").orElse(null);
        double quantity = args.has("quantity") && args.get("quantity").isNumber() ? args.get("quantity").asDouble() : 0.0;

        ObjectNode out = JSON.createObjectNode();

        if (args.has("farmGatePrice") && args.has("mandiPrice")) {
            double farmGatePrice = args.get("farmGatePrice").asDouble();
            double mandiPrice = args.get("mandiPrice").asDouble();
            FreightEstimator.NetComparison cmp = estimator.compareFarmGateVsMandi(
                    from.get(), to.get(), distanceKm, vehicle, quantity, farmGatePrice, mandiPrice
            );
            out.put("origin", cmp.freight().origin());
            out.put("destination", cmp.freight().destination());
            out.put("distanceKm", cmp.freight().distanceKm());
            out.put("vehicle", cmp.freight().vehicle().en());
            out.put("vehicleHi", cmp.freight().vehicle().hi());
            out.put("freightRupeesPerQuintal", cmp.freight().freightRupeesPerQuintal());
            out.put("totalTripCostRupees", cmp.freight().totalTripCostRupees());
            out.put("farmGatePrice", cmp.farmGatePrice());
            out.put("mandiPrice", cmp.mandiPrice());
            out.put("mandiNetRealization", cmp.mandiNetRealization());
            out.put("differenceRupeesPerQuintal", cmp.differenceRupeesPerQuintal());
            out.put("farmGateBetter", cmp.farmGateBetter());
            out.put("adviceEn", cmp.adviceEn());
            out.put("adviceHi", cmp.adviceHi());
        } else {
            FreightEstimator.FreightEstimate est = estimator.estimate(from.get(), to.get(), distanceKm, vehicle, quantity);
            out.put("origin", est.origin());
            out.put("destination", est.destination());
            out.put("distanceKm", est.distanceKm());
            out.put("vehicle", est.vehicle().en());
            out.put("vehicleHi", est.vehicle().hi());
            out.put("capacityQuintals", est.vehicle().capacityQuintals());
            out.put("freightRupeesPerQuintal", est.freightRupeesPerQuintal());
            out.put("totalTripCostRupees", est.totalTripCostRupees());
        }

        return ToolResult.ok(out.toString());
    }
}
