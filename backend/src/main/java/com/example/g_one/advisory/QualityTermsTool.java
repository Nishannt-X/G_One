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
 * Core Tool wrapper for {@link QualityTerms}. Extracts moisture %, FAQ grade, crop variety, and bag type.
 */
@Component
public class QualityTermsTool implements Tool {
    public static final String NAME = "extract_quality_terms";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode SCHEMA = ToolArgs.schema("""
            {"type": "object",
             "properties": {
               "text": {"type": "string", "description": "Spoken description or negotiation turn containing crop quality and packaging details"}
             },
             "required": ["text"]}""");

    private final QualityTerms terms = new QualityTerms();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Extract structured agricultural quality terms including moisture percentage, FAQ grade, crop variety, and packaging bag type from negotiation speech.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        Optional<String> text = ToolArgs.text(args, "text");
        if (text.isEmpty() || text.get().isBlank()) {
            return ToolResult.error("text is required.");
        }

        QualityTerms.ExtractedTerms extracted = terms.extract(text.get());
        ObjectNode out = JSON.createObjectNode();
        out.put("hasAny", extracted.hasAny());
        if (extracted.moisturePercent() != null) {
            out.put("moisturePercent", extracted.moisturePercent());
        } else {
            out.putNull("moisturePercent");
        }
        if (extracted.faqGrade() != null) {
            out.put("faqGrade", extracted.faqGrade());
        } else {
            out.putNull("faqGrade");
        }
        if (extracted.variety() != null) {
            out.put("variety", extracted.variety());
        } else {
            out.putNull("variety");
        }
        if (extracted.bagType() != null) {
            out.put("bagType", extracted.bagType());
        } else {
            out.putNull("bagType");
        }
        out.put("summaryEn", extracted.summaryEn());
        out.put("summaryHi", extracted.summaryHi());

        return ToolResult.ok(out.toString());
    }
}
