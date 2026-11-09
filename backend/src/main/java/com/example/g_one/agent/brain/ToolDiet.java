package com.example.g_one.agent.brain;

import com.example.g_one.agent.llm.ToolSpec;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.core.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which tools one farmer turn is offered, in their shortest wire form. Every schema is sent on every model call and
 * counts against the provider's tokens-per-minute limit, so a turn only sees what it can plausibly use. The tools
 * themselves still enforce their rules; this only decides what the model is told about.
 */
final class ToolDiet {
    static final String MANDI = "get_mandi_price";
    static final String WEATHER = "get_weather";
    static final String FIND = "find_buyers";
    static final String CONNECT = "connect_buyer";
    static final String SAVE = "save_profile_fact";
    static final String FORGET = "forget_me";
    static final String CONFIRM = "confirm_name";
    static final String END = "end_call";

    private static final Map<String, String> SHORT = Map.of(
            MANDI, "Dated price reference for a crop. Use for any price question.",
            WEATHER, "Forecast for a district.",
            FIND, "Find buyers when the farmer wants to sell. quantity in quintals.",
            CONNECT, "Phone a suggested buyer, only after a clear yes.",
            SAVE, "Save a stable fact the farmer stated. crops is comma separated.",
            FORGET, "Delete everything remembered, when asked.",
            CONFIRM, "Record the name check answer: true only for a clear yes.",
            END, "Hang up after your goodbye.");

    private ToolDiet() {}

    /** The names worth offering for a turn with these intents. */
    static Set<String> relevant(Set<TurnIntent.Kind> intents, boolean cropNamed, boolean unreadable,
                                MemoryGate.State state, boolean buyersSuggested) {
        Set<String> out = new java.util.HashSet<>(Set.of(END));
        if (state.maySave()) out.add(SAVE);
        if (state == MemoryGate.State.NAME_CHECK) out.add(CONFIRM);
        if (intents.contains(TurnIntent.Kind.FORGET)) out.add(FORGET);
        if (unreadable || cropNamed || intents.contains(TurnIntent.Kind.PRICE)) out.add(MANDI);
        if (unreadable || intents.contains(TurnIntent.Kind.WEATHER)) out.add(WEATHER);
        if (unreadable || cropNamed || intents.contains(TurnIntent.Kind.SELL) || buyersSuggested) out.add(FIND);
        if (buyersSuggested) out.add(CONNECT);
        return out;
    }

    static List<ToolSpec> specs(Map<String, Tool> tools, Set<String> names, ObjectMapper json) {
        List<ToolSpec> out = new ArrayList<>();
        for (Tool t : tools.values()) {
            if (!names.contains(t.name())) continue;
            out.add(new ToolSpec(t.name(), SHORT.getOrDefault(t.name(), t.description()), lean(t.jsonSchema(), json)));
        }
        out.sort(Comparator.comparing(ToolSpec::name));
        return out;
    }

    /** The schema without its property descriptions, which the short tool text and the prompt already cover. */
    private static JsonNode lean(JsonNode schema, ObjectMapper json) {
        if (schema == null || !schema.path("properties").isObject()) return schema;
        ObjectNode copy = schema.deepCopy();
        ObjectNode props = json.createObjectNode();
        copy.path("properties").properties().forEach(p -> {
            ObjectNode prop = p.getValue().deepCopy();
            prop.remove("description");
            props.set(p.getKey(), prop);
        });
        copy.set("properties", props);
        return copy;
    }
}
