package com.example.g_one.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/**
 * A function call the model asked for. {@code argumentsJson} is the raw JSON text exactly as the model wrote it.
 * {@code extraContentJson} is provider data attached to the call (Gemini 3 puts its thought signature there) that must
 * be sent back unchanged with the call; null for providers that send none.
 */
public record ToolCall(String id, String name, String argumentsJson, String extraContentJson) {

    public ToolCall(String id, String name, String argumentsJson) {
        this(id, name, argumentsJson, null);
    }

    /**
     * @return the arguments as a JSON object; a blank argument string counts as no arguments
     * @throws IllegalArgumentException when the model wrote something that is not a JSON object
     */
    public JsonNode arguments(ObjectMapper json) {
        if (argumentsJson == null || argumentsJson.isBlank()) return json.createObjectNode();
        JsonNode node;
        try {
            node = json.readTree(argumentsJson);
        } catch (IOException e) {
            throw new IllegalArgumentException("Tool arguments are not valid JSON.");
        }
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Tool arguments are not a JSON object.");
        return node;
    }
}
