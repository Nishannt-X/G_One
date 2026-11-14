package com.example.g_one.agent.tools.memory;

import com.example.g_one.agent.llm.FixedLines;
import com.example.g_one.agent.tools.ToolArgs;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.example.g_one.memory.ForgetListener;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code forget_me()}: "मुझे भूल जाओ / forget me". Deletes the profile, gists and deals stored for this caller key
 * ({@link MemoryStore#forget}, which also keeps the rest of this call from writing), turns memory off for the rest of
 * the call, and hands the model the fixed spoken confirmation from docs/memory.md. The call records ({@link ForgetListener})
 * forget the caller first; if they cannot, nothing is deleted and the tool says so (fail closed).
 *
 * <p>Deletes nothing when the caller ID is withheld (nothing is stored), while the name check is unanswered, or when
 * the speaker said they are not the remembered farmer: someone else on a shared phone cannot erase the owner's memory.
 */
@Component
public class ForgetMeTool implements Tool {
    public static final String NAME = "forget_me";
    private static final JsonNode SCHEMA = ToolArgs.schema("{\"type\": \"object\", \"properties\": {}}");

    private final Supplier<MemoryStore> store;
    private final MemoryGate gate;
    private final FixedLines lines;
    private final Clock clock;
    /** Other stores (call records) that forget the caller first; read lazily, they may depend on the tools. */
    private final Supplier<List<ForgetListener>> listeners;

    @Autowired
    public ForgetMeTool(ObjectProvider<MemoryStore> store, MemoryGate gate, FixedLines lines,
                        ObjectProvider<ForgetListener> listeners) {
        this(store::getIfAvailable, gate, lines, Clock.systemUTC(), () -> listeners.orderedStream().toList());
    }

    /** @param store gives the store, or null when none is wired */
    public ForgetMeTool(Supplier<MemoryStore> store, MemoryGate gate, FixedLines lines, Clock clock) {
        this(store, gate, lines, clock, List::of);
    }

    /** @param listeners told before memory forgets; if one fails, nothing is forgotten and the tool says so */
    public ForgetMeTool(Supplier<MemoryStore> store, MemoryGate gate, FixedLines lines, Clock clock,
                        Supplier<List<ForgetListener>> listeners) {
        this.store = store;
        this.gate = gate;
        this.lines = lines;
        this.clock = clock;
        this.listeners = listeners;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Delete everything remembered about this farmer, when they ask you to forget them or delete their "
                + "information.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        Optional<CallerKey> key = ctx.callerKey();
        if (key.isEmpty()) {
            return ToolResult.error("Nothing to delete: the caller ID is withheld, so nothing about this call is "
                    + "remembered. Tell the farmer that plainly.");
        }
        MemoryGate.State state = gate.state(ctx);
        if (state == MemoryGate.State.NAME_CHECK) {
            return ToolResult.error("Not deleted: first ask the farmer to answer whether they are the person named in "
                    + "the greeting.");
        }
        if (!state.mayForget()) {
            return ToolResult.error("Not deleted: this is not the remembered farmer, so their request cannot delete "
                    + "that farmer's memory. Nothing from this call is saved.");
        }
        MemoryStore s = store.get();
        if (s == null) return ToolResult.error("Not deleted: memory is not available right now. Offer to try later.");
        // The call records forget first, durably: if they cannot, memory is not touched and nothing claims success.
        List<ForgetListener> told = listeners.get();
        try {
            told.forEach(l -> l.forgotten(key.get()));
        } catch (RuntimeException e) {
            return ToolResult.error("Not deleted: the forget could not be completed right now. Apologise and offer to "
                    + "try again in a moment.");
        }
        boolean had;
        try {
            had = s.forget(key.get(), ctx.callId(), clock.millis());
        } catch (RuntimeException e) {
            if (told.isEmpty()) {
                return ToolResult.error("Not deleted: memory is not available right now. Offer to try later.");
            }
            // The call records committed a durable intent to forget memory, completed at the next startup or tick.
            gate.forgotten(ctx);
            return ToolResult.ok("Accepted, not yet done: the call records are cleared and the farmer's memory will be "
                    + "forgotten shortly. Memory is off for the rest of this call. Tell the farmer plainly that you will "
                    + "forget their information; do not say it is already deleted.");
        }
        gate.forgotten(ctx);
        String line = lines.forLanguage(ctx.language().code()).forgotten();
        return ToolResult.ok((had ? "Deleted everything remembered about this farmer."
                : "Nothing was stored about this farmer.")
                + " Memory is off for the rest of this call. Tell the farmer exactly: " + line);
    }
}
