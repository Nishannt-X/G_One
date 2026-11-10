package com.example.g_one.analytics;

import com.example.g_one.agent.tools.memory.ForgetMeTool;
import com.example.g_one.agent.tools.mandi.MandiPriceTool;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Collects what a stored record may need while one call runs, then decides at the end what to keep.
 *
 * <p>Only derived data is collected: no turn, translation, reply or whisper text, no audio and no phone number. The
 * persistence rules, applied in {@link #toRecord}:
 * <ul>
 *   <li>A party's side (its language, leg, turn timings, and for the farmer the tools, the kinds of private advice and
 *       the memory snapshot) is kept only when that party said YES. NO, NONE or a missing answer keeps nothing.</li>
 *   <li>The farmer's side also needs a caller key: a withheld caller ID is never stored. A farmer who asked to be
 *       forgotten during the call is not stored either.</li>
 *   <li>The shared parts (deal terms, outcome, times and end reason) are kept when at least one side is kept.</li>
 *   <li>When no side is kept there is no record at all.</li>
 * </ul>
 */
final class CallAccumulator {
    /** Tool results kept: price lookups only, which hold market data, never a party's words. */
    private static final String PRICE_TOOL = MandiPriceTool.NAME;

    private final CallId callId;
    private final long startedMs;
    private final Map<LegRole, Side> sides = new EnumMap<>(LegRole.class);
    private final Map<LegId, LegRole> legRoles = new HashMap<>();
    private final Map<LegRole, Consent> consents = new EnumMap<>(LegRole.class);
    private final List<ConsoleEvent.ToolCalled> tools = new ArrayList<>();
    private final List<ConsoleEvent.WhisperSent> whispers = new ArrayList<>();
    private ConsoleEvent.MemoryLoaded memory;
    private ConsoleEvent.DealUpdated deal;
    private ConsoleEvent.CallEnded ended;
    private boolean forgot;
    private String farmerLanguage;

    /** One party's leg. Timings are keyed like the console's: leg id and the turn's start time. */
    private static final class Side {
        String legId;
        String source;
        String language;
        long joinedMs;
        String endReason;
        final Map<String, ObjectNode> timings = new LinkedHashMap<>();
    }

    CallAccumulator(ConsoleEvent.CallStarted started) {
        this.callId = started.callId();
        this.startedMs = started.atMs();
        this.farmerLanguage = started.language();
    }

    CallId callId() {
        return callId;
    }

    boolean ended() {
        return ended != null;
    }

    /** The farmer's caller key, when memory was loaded for this call. */
    Optional<String> farmerKey() {
        return Optional.ofNullable(memory).map(m -> m.callerKey().value());
    }

    boolean forgot() {
        return forgot;
    }

    void accept(ConsoleEvent event, ObjectMapper json) {
        switch (event) {
            case ConsoleEvent.LegJoined e -> {
                Side side = sides.computeIfAbsent(e.role(), r -> new Side());
                side.legId = e.legId().value();
                side.source = e.source().name().toLowerCase(java.util.Locale.ROOT);
                side.language = e.language();
                side.joinedMs = e.atMs();
                legRoles.put(e.legId(), e.role());
            }
            case ConsoleEvent.LegEnded e -> side(e.role()).endReason = e.reason();
            case ConsoleEvent.LanguageChanged e -> side(e.role()).language = e.to();
            case ConsoleEvent.TurnHeard e -> {
                ObjectNode t = json.createObjectNode();
                t.put("key", e.legId().value() + ":" + e.startedMs());
                t.put("legId", e.legId().value());
                t.put("role", e.role().name().toLowerCase(java.util.Locale.ROOT));
                t.put("startedMs", e.startedMs());
                t.put("endedMs", e.endedMs());
                t.put("heardMs", e.atMs());
                t.putNull("translatedMs");
                t.putNull("repliedMs");
                side(e.role()).timings.put(e.legId().value() + ":" + e.startedMs(), t);
            }
            case ConsoleEvent.TurnTranslated e -> {
                ObjectNode t = side(e.role()).timings.get(e.legId().value() + ":" + e.turnStartedMs());
                if (t != null && t.get("translatedMs").isNull()) {
                    t.put("translatedMs", e.atMs());
                }
            }
            case ConsoleEvent.AssistantSaid e -> {
                LegRole role = legRoles.getOrDefault(e.legId(), e.role());
                List<ObjectNode> list = new ArrayList<>(side(role).timings.values());
                for (int i = list.size() - 1; i >= 0; i--) {
                    ObjectNode t = list.get(i);
                    if (!t.get("legId").asText().equals(e.legId().value())) {
                        continue;
                    }
                    if (t.get("repliedMs").isNull()) {
                        t.put("repliedMs", e.atMs());
                    }
                    break;
                }
            }
            case ConsoleEvent.WhisperSent e -> whispers.add(e);
            case ConsoleEvent.ToolCalled e -> {
                tools.add(e);
                if (e.tool().equals(ForgetMeTool.NAME) && e.ok()) {
                    forgot = true;
                }
            }
            case ConsoleEvent.MemoryLoaded e -> memory = e;
            case ConsoleEvent.DealUpdated e -> deal = e;
            case ConsoleEvent.ConsentRecorded e -> consents.put(e.role(), Consent.parse(e.consent()));
            case ConsoleEvent.CallEnded e -> ended = e;
            case ConsoleEvent.CallStarted e -> farmerLanguage = e.language();
            case ConsoleEvent.TurnMeasured e -> { }
        }
    }

    private Side side(LegRole role) {
        return sides.computeIfAbsent(role, r -> new Side());
    }

    Consent consent(LegRole role) {
        return consents.getOrDefault(role, Consent.NONE);
    }

    /** Why a party's side was not kept, or null when it was. */
    private String notStoredReason(LegRole role) {
        Consent c = consent(role);
        if (role == LegRole.FARMER) {
            if (memory == null) {
                return "withheld";
            }
            if (forgot) {
                return "forgotten";
            }
        } else if (sides.get(LegRole.BUYER) == null || sides.get(LegRole.BUYER).legId == null) {
            return "no_leg";
        }
        return switch (c) {
            case YES -> null;
            case NO -> "said_no";
            case NONE -> "no_answer";
        };
    }

    /** The record to store, or empty when no party's side may be kept. */
    Optional<CallRecord> toRecord(ObjectMapper json, long nowMs) {
        String farmerWhy = notStoredReason(LegRole.FARMER);
        String buyerWhy = notStoredReason(LegRole.BUYER);
        boolean keepFarmer = farmerWhy == null;
        boolean keepBuyer = buyerWhy == null;
        if (!keepFarmer && !keepBuyer) {
            return Optional.empty();
        }
        long endedMs = ended != null ? ended.atMs() : nowMs;
        long durationMs = ended != null ? ended.durationMs() : Math.max(0, endedMs - startedMs);

        ObjectNode data = json.createObjectNode();
        data.set("deal", deal == null ? null : dealJson(json));
        ObjectNode notStored = data.putObject("notStored");
        if (farmerWhy != null) notStored.put("farmer", farmerWhy);
        if (buyerWhy != null) notStored.put("buyer", buyerWhy);
        data.set("farmer", keepFarmer ? farmerJson(json) : null);
        data.set("buyer", keepBuyer ? sideJson(json, sides.get(LegRole.BUYER)) : null);

        Map<String, String> languages = new LinkedHashMap<>();
        if (keepFarmer) languages.put("farmer", farmerLanguageNow());
        if (keepBuyer && sides.get(LegRole.BUYER).language != null) {
            languages.put("buyer", sides.get(LegRole.BUYER).language);
        }
        return Optional.of(new CallRecord(callId.value(), startedMs, endedMs, durationMs,
                ended != null ? ended.reason() : "", languages, outcome(), consent(LegRole.FARMER),
                consent(LegRole.BUYER), keepFarmer ? memory.callerKey().value() : null, data, nowMs));
    }

    private String farmerLanguageNow() {
        Side f = sides.get(LegRole.FARMER);
        return f != null && f.language != null ? f.language : farmerLanguage;
    }

    private String outcome() {
        if (deal == null) {
            return "no_terms";
        }
        boolean agreed = deal.state() == ConsoleEvent.DealState.AGREED || (deal.farmerYes() && deal.buyerYes());
        return agreed ? "agreed" : "no_deal";
    }

    private ObjectNode dealJson(ObjectMapper json) {
        ObjectNode d = json.createObjectNode();
        d.set("state", json.valueToTree(deal.state()));
        d.put("commodity", deal.commodity());
        d.put("variety", deal.variety());
        d.put("market", deal.market());
        d.put("quantity", deal.quantity());
        d.put("price", deal.price());
        d.put("unit", deal.unit());
        d.put("pickup", deal.pickup());
        d.put("farmerYes", deal.farmerYes());
        d.put("buyerYes", deal.buyerYes());
        d.put("atMs", deal.atMs());
        return d;
    }

    private ObjectNode sideJson(ObjectMapper json, Side side) {
        ObjectNode s = json.createObjectNode();
        s.put("legId", side.legId);
        s.put("source", side.source);
        s.put("language", side.language);
        s.put("joinedMs", side.joinedMs);
        s.put("endReason", side.endReason);
        ArrayNode timings = s.putArray("timings");
        side.timings.values().forEach(timings::add);
        return s;
    }

    private ObjectNode farmerJson(ObjectMapper json) {
        Side side = sides.get(LegRole.FARMER);
        ObjectNode f = side != null ? sideJson(json, side) : json.createObjectNode();
        f.put("language", farmerLanguageNow());
        ArrayNode toolList = f.putArray("tools");
        for (ConsoleEvent.ToolCalled t : tools) {
            ObjectNode n = toolList.addObject();
            n.put("tool", t.tool());
            n.put("ok", t.ok());
            n.put("durationMs", t.durationMs());
            n.put("atMs", t.atMs());
            // Arguments are never kept; a price lookup's result is market data, so the price comparison can be shown.
            if (t.tool().equals(PRICE_TOOL)) {
                n.put("result", t.result());
            }
        }
        ArrayNode advice = f.putArray("advice");
        for (ConsoleEvent.WhisperSent w : whispers) {
            ObjectNode n = advice.addObject();
            n.set("kind", json.valueToTree(w.kind()));
            n.put("atMs", w.atMs());
        }
        ObjectNode m = f.putObject("memory");
        m.put("returning", memory.returning());
        m.set("facts", json.valueToTree(memory.facts()));
        m.set("gists", json.valueToTree(memory.gists()));
        return f;
    }
}
