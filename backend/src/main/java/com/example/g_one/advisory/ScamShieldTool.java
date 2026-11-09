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
 * Core Tool wrapper for {@link ScamShield}. Screens spoken input for suspicious payment and urgency patterns.
 */
@Component
public class ScamShieldTool implements Tool {
    public static final String NAME = "scam_shield";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode SCHEMA = ToolArgs.schema("""
            {"type": "object",
             "properties": {
               "text": {"type": "string", "description": "The speaker's statement to screen for suspicious scam patterns"}
             },
             "required": ["text"]}""");

    private final ScamShield shield = new ScamShield();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Screen buyer or caller statements for scam risks such as QR scans, UPI PIN entry, OTP requests, advance-fee asks, or urgency pressure.";
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
        ScamShield.Detection detection = shield.screen(text.get());
        ObjectNode out = JSON.createObjectNode();
        out.put("detected", detection.detected());
        out.put("category", detection.category().name());
        out.put("severity", detection.severity().name());
        out.put("whisperTextEn", detection.whisperTextEn());
        out.put("whisperTextHi", detection.whisperTextHi());
        out.put("matchedPattern", detection.matchedPattern());
        return ToolResult.ok(out.toString());
    }
}
