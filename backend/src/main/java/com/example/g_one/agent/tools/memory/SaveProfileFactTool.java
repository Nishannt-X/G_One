package com.example.g_one.agent.tools.memory;

import com.example.g_one.agent.tools.ToolArgs;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallerKey;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.example.g_one.memory.ProfileFields;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code save_profile_fact(field, value)}: notes one stable fact (kept for the call; stored only if the farmer agrees at
 * the end of the call that it may be remembered, see {@link PendingFacts}) the farmer said about themselves, with provenance
 * "said" and this call's id. Writes nothing (and says so honestly) when memory is off: a withheld caller ID, an
 * unanswered name check, another speaker, or after {@code forget_me}.
 *
 * <p>The memory area decides which {@link MemoryStore} is wired; until one is, this tool says memory is unavailable.
 */
@Component
public class SaveProfileFactTool implements Tool {
    public static final String NAME = "save_profile_fact";
    /** The profile fields from docs/memory.md. */
    public static final List<String> FIELDS = List.of("name", "village", "district", "state", "crops", "usualMandi",
            "language", "typicalQuantity", "transport", "paymentPreference", "storageAccess");
    static final int MAX_VALUE_CHARS = 80;
    /** Fields whose value {@link ProfileFields#check} decides: names and places, and the newer structured fields. */
    private static final Set<String> CHECKED = Set.of(ProfileFields.NAME, ProfileFields.VILLAGE,
            ProfileFields.DISTRICT, ProfileFields.STATE, ProfileFields.USUAL_MANDI, ProfileFields.TYPICAL_QUANTITY,
            ProfileFields.TRANSPORT, ProfileFields.PAYMENT_PREFERENCE, ProfileFields.STORAGE_ACCESS);
    /** Six or more digits, even with spaces or dashes between them: a phone, account, Aadhaar or OTP. Never saved. */
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d(?:[\\s-]?\\d){5,}");
    private static final JsonNode SCHEMA = ToolArgs.schema("""
            {"type": "object",
             "properties": {
               "field": {"type": "string", "enum": ["name", "village", "district", "state", "crops", "usualMandi", "language",
                 "typicalQuantity", "transport", "paymentPreference", "storageAccess"],
                 "description": "Which fact. crops is the full list the farmer grows, comma separated; usualMandi is the market they usually sell at; typicalQuantity is how much they usually sell at once; transport is own vehicle, needs truck or buyer arranges; paymentPreference is cash, UPI, bank transfer or on delivery; storageAccess is has cold storage, cold storage nearby or no cold storage."},
               "value": {"type": "string", "description": "The fact, short. Names and places in English letters, for example Ramesh Patil, Nashik, or red onion, tomato"}},
             "required": ["field", "value"]}""");

    private final Supplier<MemoryStore> store;
    private final MemoryGate gate;
    private final Clock clock;
    /** Null only for tests that check the tool's own rules against a store directly. */
    private final PendingFacts pending;

    @Autowired
    public SaveProfileFactTool(ObjectProvider<MemoryStore> store, MemoryGate gate, PendingFacts pending) {
        this(store::getIfAvailable, gate, Clock.systemUTC(), pending);
    }

    /** Writes straight to the store: for tests of the tool's validation only. */
    public SaveProfileFactTool(Supplier<MemoryStore> store, MemoryGate gate, Clock clock) {
        this(store, gate, clock, null);
    }

    /**
     * @param store gives the store, or null when none is wired
     * @param pending holds facts until the farmer agrees, at the end of the call, that the call may be remembered
     */
    public SaveProfileFactTool(Supplier<MemoryStore> store, MemoryGate gate, Clock clock, PendingFacts pending) {
        this.store = store;
        this.gate = gate;
        this.clock = clock;
        this.pending = pending;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Save one stable fact the farmer clearly told you about themselves (name, village, district, state, "
                + "crops, usual mandi, preferred language, typical quantity, transport, payment preference, cold "
                + "storage) so the next call does not start from zero. Write names in English letters.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        Optional<String> field = ToolArgs.text(args, "field");
        if (field.isEmpty() || !FIELDS.contains(field.get())) {
            return ToolResult.error("field must be one of " + String.join(", ", FIELDS) + ".");
        }
        String value = MemoryBlock.clean(ToolArgs.text(args, "value").orElse(""), Integer.MAX_VALUE);
        if (value.isEmpty()) return ToolResult.error("value is required.");
        if (value.codePointCount(0, value.length()) > MAX_VALUE_CHARS) {
            return ToolResult.error("value is too long; save a short phrase.");
        }
        if (LONG_NUMBER.matcher(value).find()) {
            return ToolResult.error("Not saved: never save long numbers such as phone, account, Aadhaar or OTP.");
        }
        if (CHECKED.contains(field.get())) {
            // Names and places in English letters (transliterated when given in another script); fixed values for
            // transport, payment and storage.
            Optional<ProfileFields.Checked> checked = ProfileFields.check(field.get(), value);
            if (checked.isEmpty()) return ToolResult.error("Not saved: " + hint(field.get()));
            value = checked.get().value();
        }
        Optional<CallerKey> key = ctx.callerKey();
        if (key.isEmpty()) {
            return ToolResult.error("Not saved: memory is off for this call because the caller ID is withheld.");
        }
        MemoryGate.State state = gate.state(ctx);
        if (!state.maySave()) return ToolResult.error("Not saved: " + why(state));
        MemoryStore s = store.get();
        if (s == null) return ToolResult.error("Not saved: memory is not available right now.");
        ProfileFact fact = new ProfileFact(field.get(), value, Provenance.said(ctx.callId(), clock.millis()));
        if (pending != null) {
            // Kept for this call only; stored only if the farmer says yes to being remembered at the end of the call.
            pending.add(ctx.callId(), key.get(), fact);
            return ToolResult.ok("Noted " + field.get() + " for this call.");
        }
        s.upsertFacts(key.get(), List.of(fact));
        return ToolResult.ok("Saved " + field.get() + ".");
    }

    static String hint(String field) {
        return switch (field) {
            case ProfileFields.TRANSPORT -> "transport must be one of " + String.join(", ", ProfileFields.TRANSPORTS) + ".";
            case ProfileFields.PAYMENT_PREFERENCE ->
                    "paymentPreference must be one of " + String.join(", ", ProfileFields.PAYMENTS) + ".";
            case ProfileFields.STORAGE_ACCESS ->
                    "storageAccess must be one of " + String.join(", ", ProfileFields.STORAGE) + ".";
            case ProfileFields.TYPICAL_QUANTITY -> "typicalQuantity must be a short amount, for example 40 quintal per sale.";
            default -> field + " must be a short name in English letters, for example Ramesh Patil.";
        };
    }

    static String why(MemoryGate.State state) {
        return switch (state) {
            case NAME_CHECK -> "the farmer has not answered the name check yet.";
            case OTHER_SPEAKER -> "this is not the remembered farmer, so memory is off for this call.";
            case FORGOTTEN -> "the farmer asked to be forgotten, so memory is off for the rest of this call.";
            case WITHHELD -> "memory is off for this call because the caller ID is withheld.";
            case NEW, UNNAMED, CONFIRMED -> "memory is off for this call.";
        };
    }
}
