package com.example.g_one.analytics;

import com.example.g_one.agent.tools.buyers.ConnectBuyerTool;
import com.example.g_one.agent.tools.buyers.FindBuyersTool;
import com.example.g_one.agent.tools.mandi.MandiPriceTool;
import com.example.g_one.agent.tools.memory.ConfirmNameTool;
import com.example.g_one.agent.tools.memory.ForgetMeTool;
import com.example.g_one.agent.tools.memory.SaveProfileFactTool;
import com.example.g_one.agent.tools.weather.WeatherTool;
import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Collects what a record may hold while one call runs, then builds it at the end. Every ended call gets a record; the
 * parties' consent decides only the conversation-derived fields (owner decision, ticket #19; privacy review round 1).
 *
 * <p>Only derived data is collected: no turn, translation, reply, whisper or aside text, no audio, no phone number, no
 * tool arguments and no tool results. Arguments are read while the call runs only to tell a confirmed name from a
 * denied one, to know which directory buyer was connected (looked up by id) and which name the farmer stated; they are
 * never kept as arguments. Deal terms are reduced to closed values by {@link DealCard}.
 *
 * <p>What is kept (schema version {@value CallRecord#SCHEMA_VERSION}):
 * <table>
 *   <caption>Fields and when they are kept</caption>
 *   <tr><th>Field</th><th>Kept when</th></tr>
 *   <tr><td>times, duration, end reason, outcome, who joined ({@code joined}), each party's consent answer</td>
 *       <td>every call</td></tr>
 *   <tr><td>{@code farmer} and {@code buyer} leg: language and switches, leg times, {@code latency} (turn count and
 *       per-stage medians with their counts); {@code farmer.tools} (name, ok, duration, time and offset);
 *       {@code farmer.advice} (kind and time); {@code farmer.identity} (the name check's flag); {@code buyer.match}
 *       (directory buyer by id); {@code price} (that a mandi lookup ran, and when); {@code brain} (assistant turn
 *       counts and latency)</td><td>every call</td></tr>
 *   <tr><td>{@code deal}: crop and market from the catalog, bounded quantity and price with listed units, basis,
 *       total, pickup and payment kinds with fixed labels, each party's confirmation, the directory buyer</td>
 *       <td>every call; {@code termsOnly} and the label "kept without consent: terms only" when no party's recorded
 *       answer was yes</td></tr>
 *   <tr><td>a leg's {@code timings} (per-turn start, end, heard, translated and replied times)</td>
 *       <td>that party said yes (the farmer's, unless withdrawn by a forget)</td></tr>
 *   <tr><td>the caller key (column, never sent to the browser), {@code farmer.statedName}</td>
 *       <td>farmer YES, with a caller ID, and no forget of that caller during the call</td></tr>
 *   <tr><td>{@code farmer.memory}: what was remembered when the call began (facts, newest gists)</td>
 *       <td>as the caller key, and the caller did not deny being the remembered farmer</td></tr>
 * </table>
 * {@code consentScope} and {@code termsOnly} follow the recorded answers only; {@code keyed} says whether the farmer's
 * linked fields were kept, which a withheld caller ID or a forget can prevent even after a yes.
 */
final class CallAccumulator {
    /** Lookups that make a call without terms "information only". */
    private static final Set<String> INFO_TOOLS = Set.of(MandiPriceTool.NAME, WeatherTool.NAME, FindBuyersTool.NAME);
    /** Newest remembered gists kept with the memory snapshot: the last call and the open follow-ups. */
    static final int MAX_GISTS = 3;
    static final String TERMS_ONLY_LABEL = "kept without consent: terms only";

    private final CallId callId;
    private final long startedMs;
    private final String startLanguage;
    private final Map<LegRole, Side> sides = new EnumMap<>(LegRole.class);
    private final Map<LegId, LegRole> legRoles = new HashMap<>();
    private final Map<LegRole, Consent> consents = new EnumMap<>(LegRole.class);
    private final List<ConsoleEvent.ToolCalled> tools = new ArrayList<>();
    private final List<ConsoleEvent.WhisperSent> whispers = new ArrayList<>();
    private final Map<String, Long> dealStates = new LinkedHashMap<>();
    private final List<ConsoleEvent.TurnMeasured> brainTurns = new ArrayList<>();
    /** Turns that were a private question to G-One: their timings are dropped too. */
    private final Set<String> asideTurns = new HashSet<>();
    private ConsoleEvent.MemoryLoaded memory;
    private ConsoleEvent.DealUpdated deal;
    private ConsoleEvent.CallEnded ended;
    private Long priceLookupMs;
    private Long farmerYesMs;
    private Long buyerYesMs;
    /** When this caller said "forget me" on this call, or null. */
    private Long forgetMeMs;
    /** The caller key was forgotten elsewhere (the console, or another call) while this call was open. */
    private boolean withdrawn;
    /** "confirmed", "denied" or "unchecked": the returning farmer's name check. */
    private String identity = "unchecked";
    /** The connect_buyer id as the model sent it: kept only if it is a directory id. */
    private String buyerId;
    private String farmerLanguage;
    /** The name the farmer stated on this call: kept only with the farmer's yes. */
    private String statedName;

    /** One party's leg. Timings are keyed like the console's: leg id and the turn's start time. */
    private static final class Side {
        String legId;
        String source;
        String language;
        String firstLanguage;
        long joinedMs;
        Long endedMs;
        String endReason;
        int turnSeq;
        final List<ConsoleEvent.LanguageChanged> changes = new ArrayList<>();
        final Map<String, ObjectNode> timings = new LinkedHashMap<>();
    }

    CallAccumulator(ConsoleEvent.CallStarted started) {
        this.callId = started.callId();
        this.startedMs = started.atMs();
        this.farmerLanguage = started.language();
        this.startLanguage = started.language();
    }

    CallId callId() {
        return callId;
    }

    long startedMs() {
        return startedMs;
    }

    boolean ended() {
        return ended != null;
    }

    /** The farmer's caller key, when memory was loaded for this call. */
    Optional<String> farmerKey() {
        return Optional.ofNullable(memory).map(m -> m.callerKey().value());
    }

    /** When the caller said "forget me" on this call, if they did. */
    Optional<Long> forgetMeMs() {
        return Optional.ofNullable(forgetMeMs);
    }

    /** The caller key was forgotten while this call was open: its linked fields are not kept. */
    void withdrawKey() {
        withdrawn = true;
    }

    void accept(ConsoleEvent event, ObjectMapper json) {
        switch (event) {
            case ConsoleEvent.LegJoined e -> {
                Side side = sides.computeIfAbsent(e.role(), r -> new Side());
                side.legId = e.legId().value();
                side.source = e.source().name().toLowerCase(Locale.ROOT);
                side.language = e.language();
                side.firstLanguage = e.language();
                side.joinedMs = e.atMs();
                legRoles.put(e.legId(), e.role());
            }
            case ConsoleEvent.LegEnded e -> {
                Side side = side(e.role());
                side.endReason = e.reason();
                side.endedMs = e.atMs();
            }
            case ConsoleEvent.LanguageChanged e -> {
                Side side = side(e.role());
                side.language = e.to();
                side.changes.add(e);
            }
            case ConsoleEvent.TurnHeard e -> {
                if (asideTurns.contains(e.legId().value() + ":" + e.startedMs())) {
                    return;
                }
                ObjectNode t = json.createObjectNode();
                // A display key only: the leg and the turn's number, never a time or anything said.
                Side heardSide = side(e.role());
                t.put("key", e.legId().value() + "#" + (++heardSide.turnSeq));
                t.put("legId", e.legId().value());
                t.put("role", e.role().name().toLowerCase(Locale.ROOT));
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
            case ConsoleEvent.ToolCalled e -> onTool(e);
            case ConsoleEvent.MemoryLoaded e -> memory = e;
            case ConsoleEvent.DealUpdated e -> {
                deal = e;
                if (e.state() != null) dealStates.putIfAbsent(e.state().wire(), e.atMs());
                if (e.farmerYes() && farmerYesMs == null) farmerYesMs = e.atMs();
                if (e.buyerYes() && buyerYesMs == null) buyerYesMs = e.atMs();
                // A new set of terms needs new yeses.
                if (!e.farmerYes()) farmerYesMs = null;
                if (!e.buyerYes()) buyerYesMs = null;
            }
            case ConsoleEvent.ConsentRecorded e -> consents.put(e.role(), Consent.parse(e.consent()));
            case ConsoleEvent.CallEnded e -> ended = e;
            case ConsoleEvent.CallStarted e -> farmerLanguage = e.language();
            case ConsoleEvent.TurnMeasured e -> brainTurns.add(e);
            // A party's private question to G-One: nothing of it is stored, not even a timing.
            case ConsoleEvent.AsideAnswered e -> {
                String key = e.legId().value() + ":" + e.startedMs();
                asideTurns.add(key);
                side(e.role()).timings.remove(key);
            }
        }
    }

    private void onTool(ConsoleEvent.ToolCalled e) {
        tools.add(e);
        if (!e.ok()) {
            return;
        }
        switch (e.tool()) {
            case ForgetMeTool.NAME -> {
                if (forgetMeMs == null) forgetMeMs = e.atMs();
            }
            // Only that a lookup ran, and when: the tool's result is never kept.
            case MandiPriceTool.NAME -> priceLookupMs = e.atMs();
            case ConfirmNameTool.NAME -> {
                JsonNode confirmed = e.args() == null ? null : e.args().get("confirmed");
                if (confirmed != null && confirmed.isBoolean()) {
                    identity = confirmed.asBoolean() ? "confirmed" : "denied";
                }
            }
            case SaveProfileFactTool.NAME -> {
                JsonNode field = e.args() == null ? null : e.args().get("field");
                JsonNode value = e.args() == null ? null : e.args().get("value");
                if (field != null && "name".equals(field.asText()) && value != null && value.isTextual()) {
                    // Only a plain name (Vocabulary.statedName); anything else is not kept at all.
                    Vocabulary.statedName(value.asText()).ifPresent(n -> statedName = n);
                }
            }
            case ConnectBuyerTool.NAME -> {
                // The id only, looked up in the directory when the record is built; the tool's result text is unused.
                JsonNode id = e.args() == null ? null : e.args().get("buyerId");
                if (id != null && id.isTextual() && id.asText().length() <= 64) buyerId = id.asText();
            }
            default -> { }
        }
    }

    private Side side(LegRole role) {
        return sides.computeIfAbsent(role, r -> new Side());
    }

    Consent consent(LegRole role) {
        return consents.getOrDefault(role, Consent.NONE);
    }

    private boolean buyerJoined() {
        Side b = sides.get(LegRole.BUYER);
        return b != null && b.legId != null;
    }

    private boolean farmerWithdrew() {
        return forgetMeMs != null || withdrawn;
    }

    /** "both", "farmer", "buyer" or "none": whose recorded answer was yes. */
    String consentScope() {
        boolean f = consent(LegRole.FARMER).yes();
        boolean b = consent(LegRole.BUYER).yes();
        return f && b ? "both" : f ? "farmer" : b ? "buyer" : "none";
    }

    /** The caller key, stated name and memory may be kept: the farmer said yes, with a caller ID, and no forget. */
    private boolean keyed() {
        return consent(LegRole.FARMER).yes() && memory != null && !farmerWithdrew();
    }

    /** A party's per-turn timeline may be kept: that party said yes (the farmer's yes not withdrawn by a forget). */
    private boolean timelineKept(LegRole role) {
        if (role == LegRole.FARMER) return consent(LegRole.FARMER).yes() && !farmerWithdrew();
        return consent(LegRole.BUYER).yes() && buyerJoined();
    }

    /** Why the farmer's consent-gated fields (caller key, stated name, memory) were not kept, or null when they were. */
    private String farmerNotKeptReason() {
        if (memory == null) {
            return "withheld";
        }
        if (farmerWithdrew()) {
            return "forgotten";
        }
        return switch (consent(LegRole.FARMER)) {
            case YES -> null;
            case NO -> "said_no";
            case NONE -> "no_answer";
        };
    }

    /**
     * The smallest record of this call (id, times, the recorded consent answers), for when the full record cannot be
     * built. It reads nothing but those fields, so it cannot fail.
     */
    CallRecord minimal(RecordSchema schema, long nowMs) {
        long endedMs = ended != null ? ended.atMs() : nowMs;
        long durationMs = ended != null ? ended.durationMs() : Math.max(0, endedMs - startedMs);
        return schema.minimal(callId.value(), startedMs, endedMs, durationMs, consent(LegRole.FARMER),
                consent(LegRole.BUYER), nowMs, CallRecord.SCHEMA_VERSION);
    }

    /**
     * The record of this call, before {@link RecordSchema#project} maps every string to its closed set (the writer
     * never keeps it unprojected). Every ended call has one; consent decides only the gated fields.
     */
    CallRecord toRecord(ObjectMapper json, DealVocabulary vocab, long nowMs, String endReasonIfOpen) {
        String scope = consentScope();
        boolean termsOnly = scope.equals("none");
        boolean keyed = keyed();
        long endedMs = ended != null ? ended.atMs() : nowMs;
        long durationMs = ended != null ? ended.durationMs() : Math.max(0, endedMs - startedMs);

        ObjectNode data = json.createObjectNode();
        data.put("consentScope", scope);
        data.put("termsOnly", termsOnly);
        data.put("keyed", keyed);
        if (ended == null) data.put("incomplete", true);
        ObjectNode joined = data.putObject("joined");
        Side farmerSide = sides.get(LegRole.FARMER);
        joined.put("farmer", farmerSide != null && farmerSide.legId != null);
        joined.put("buyer", buyerJoined());
        data.set("deal", deal == null ? null : dealJson(json, vocab, termsOnly));
        data.set("price", priceJson(json));
        ObjectNode notStored = data.putObject("notStored");
        String farmerWhy = keyed ? null : farmerNotKeptReason();
        if (farmerWhy != null) notStored.put("farmer", farmerWhy);
        if (keyed && identity.equals("denied")) notStored.put("memory", "not_this_farmer");
        data.set("farmer", farmerJson(json, keyed));
        data.set("buyer", buyerJoined() ? buyerJson(json, vocab) : null);
        data.set("brain", brainJson(json));

        Map<String, String> languages = new LinkedHashMap<>();
        languages.put("farmer", farmerLanguageNow());
        if (buyerJoined() && sides.get(LegRole.BUYER).language != null) {
            languages.put("buyer", sides.get(LegRole.BUYER).language);
        }
        String reason = ended != null ? ended.reason() : endReasonIfOpen;
        return new CallRecord(callId.value(), startedMs, endedMs, durationMs, reason, languages, outcome(),
                consent(LegRole.FARMER), consent(LegRole.BUYER), keyed ? memory.callerKey().value() : null, data, nowMs,
                CallRecord.SCHEMA_VERSION);
    }

    private String farmerLanguageNow() {
        Side f = sides.get(LegRole.FARMER);
        return f != null && f.language != null ? f.language : farmerLanguage;
    }

    /** "agreed", "no_deal" (terms heard, not agreed), "info_only" (lookups, no buyer, no terms) or "no_terms". */
    private String outcome() {
        if (deal == null) {
            boolean lookedUp = tools.stream().anyMatch(t -> t.ok() && INFO_TOOLS.contains(t.tool()));
            return lookedUp && !buyerJoined() ? "info_only" : "no_terms";
        }
        boolean agreed = deal.state() == ConsoleEvent.DealState.AGREED || (deal.farmerYes() && deal.buyerYes());
        return agreed ? "agreed" : "no_deal";
    }

    /** The deal card: closed values only, kept for every call, labelled when no party said yes. */
    private ObjectNode dealJson(ObjectMapper json, DealVocabulary vocab, boolean termsOnly) {
        ObjectNode d = json.createObjectNode();
        d.set("state", json.valueToTree(deal.state()));
        DealCard.fill(d, vocab, deal.commodity(), deal.market(), deal.quantity(), deal.price(), deal.unit(),
                deal.pickup(), buyerId);
        d.put("farmerYes", deal.farmerYes());
        d.put("buyerYes", deal.buyerYes());
        d.put("atMs", deal.atMs());
        ObjectNode states = d.putObject("stateTimes");
        dealStates.forEach(states::put);
        d.put("readback", dealStates.containsKey("readback") || dealStates.containsKey("confirming")
                || dealStates.containsKey("agreed"));
        if (farmerYesMs != null) d.put("farmerYesMs", farmerYesMs);
        if (buyerYesMs != null) d.put("buyerYesMs", buyerYesMs);
        d.put("termsOnly", termsOnly);
        if (termsOnly) d.put("label", TERMS_ONLY_LABEL);
        return d;
    }

    /** That a mandi price lookup ran, and when. The tool's result (prices, words) is never kept. */
    private ObjectNode priceJson(ObjectMapper json) {
        if (priceLookupMs == null) {
            return null;
        }
        ObjectNode p = json.createObjectNode();
        p.put("tool", MandiPriceTool.NAME);
        p.put("atMs", priceLookupMs);
        p.put("offsetMs", Math.max(0, priceLookupMs - startedMs));
        return p;
    }

    private ObjectNode sideJson(ObjectMapper json, Side side, boolean timeline) {
        ObjectNode s = json.createObjectNode();
        s.put("legId", side.legId);
        s.put("source", side.source);
        s.put("language", side.language);
        s.put("firstLanguage", side.firstLanguage);
        s.put("joinedMs", side.joinedMs);
        if (side.endedMs != null) s.put("endedMs", side.endedMs);
        s.put("endReason", side.endReason);
        ArrayNode changes = s.putArray("languageChanges");
        for (ConsoleEvent.LanguageChanged c : side.changes) {
            ObjectNode n = changes.addObject();
            n.put("from", c.from());
            n.put("to", c.to());
            n.put("reason", c.reason());
            n.put("atMs", c.atMs());
        }
        if (timeline) {
            ArrayNode timings = s.putArray("timings");
            side.timings.values().forEach(timings::add);
        }
        s.set("latency", latencyJson(json, side.timings.values()));
        return s;
    }

    /**
     * Per-stage medians in milliseconds with how many turns each was measured on, worked out like the console's: a
     * stage with nothing measured is absent. Kept for every call; the per-turn timeline is not.
     */
    static ObjectNode latencyJson(ObjectMapper json, Iterable<? extends JsonNode> timings) {
        List<Long> transcript = new ArrayList<>();
        List<Long> translation = new ArrayList<>();
        List<Long> reply = new ArrayList<>();
        int turns = 0;
        for (JsonNode t : timings) {
            turns++;
            long heard = t.path("heardMs").asLong();
            transcript.add(Math.max(0, heard - t.path("endedMs").asLong()));
            if (t.path("translatedMs").isNumber()) translation.add(Math.max(0, t.get("translatedMs").asLong() - heard));
            if (t.path("repliedMs").isNumber()) reply.add(Math.max(0, t.get("repliedMs").asLong() - heard));
        }
        ObjectNode l = json.createObjectNode();
        l.put("turns", turns);
        putMedian(l, "transcript", transcript);
        putMedian(l, "translation", translation);
        putMedian(l, "reply", reply);
        return l;
    }

    /** {@code key}: the median, and {@code keyCount}: how many values it was taken over. */
    private static void putMedian(ObjectNode out, String key, List<Long> values) {
        if (values.isEmpty()) {
            return;
        }
        out.put(key, median(values));
        out.put(key + "Count", values.size());
    }

    static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    private ObjectNode buyerJson(ObjectMapper json, DealVocabulary vocab) {
        ObjectNode b = sideJson(json, sides.get(LegRole.BUYER), timelineKept(LegRole.BUYER));
        vocab.buyer(buyerId).ifPresent(directory -> {
            ObjectNode m = b.putObject("match");
            m.put("buyerId", directory.id());
            m.put("name", directory.name());
        });
        return b;
    }

    /** The farmer leg's operational data; the memory snapshot and stated name only when {@code keyed}. */
    private ObjectNode farmerJson(ObjectMapper json, boolean keyed) {
        Side side = sides.get(LegRole.FARMER);
        ObjectNode f = side != null ? sideJson(json, side, timelineKept(LegRole.FARMER)) : json.createObjectNode();
        f.put("language", farmerLanguageNow());
        if (side == null || side.firstLanguage == null) f.put("firstLanguage", startLanguage);
        f.put("identity", identity);
        ArrayNode toolList = f.putArray("tools");
        for (ConsoleEvent.ToolCalled t : tools) {
            // Name, outcome and timing only: never the arguments or the result.
            ObjectNode n = toolList.addObject();
            n.put("tool", t.tool());
            n.put("ok", t.ok());
            n.put("durationMs", t.durationMs());
            n.put("atMs", t.atMs());
            n.put("offsetMs", Math.max(0, t.atMs() - startedMs));
        }
        ArrayNode advice = f.putArray("advice");
        for (ConsoleEvent.WhisperSent w : whispers) {
            ObjectNode n = advice.addObject();
            n.set("kind", json.valueToTree(w.kind()));
            n.put("atMs", w.atMs());
            n.put("offsetMs", Math.max(0, w.atMs() - startedMs));
        }
        if (!keyed || identity.equals("denied")) {
            // Without the farmer's yes nothing remembered is attached; after a denied name check the remembered
            // farmer is not this caller.
            f.putNull("memory");
        } else {
            ObjectNode m = f.putObject("memory");
            m.put("returning", memory.returning());
            m.set("facts", json.valueToTree(memory.facts()));
            List<CallGist> newest = memory.gists().stream()
                    .sorted(Comparator.comparingLong(CallGist::startedMs).reversed()).limit(MAX_GISTS).toList();
            m.set("gists", json.valueToTree(newest));
        }
        if (keyed && statedName != null) {
            f.put("statedName", statedName);
        }
        return f;
    }

    /** The assistant's turns: counts, the median turn latency, tokens, and handlers and failures by kind. */
    private ObjectNode brainJson(ObjectMapper json) {
        ObjectNode b = json.createObjectNode();
        b.put("turns", brainTurns.size());
        List<Long> latency = new ArrayList<>();
        long modelCalls = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        Map<String, Integer> handlers = new TreeMap<>();
        Map<String, Integer> failures = new TreeMap<>();
        for (ConsoleEvent.TurnMeasured t : brainTurns) {
            latency.add(Math.max(0, t.latencyMs()));
            modelCalls += Math.max(0, t.modelCalls());
            promptTokens += Math.max(0, t.promptTokens());
            completionTokens += Math.max(0, t.completionTokens());
            handlers.merge(String.valueOf(t.handler()), 1, Integer::sum);
            if (t.failure() != null && !t.failure().isBlank()) failures.merge(String.valueOf(t.failure()), 1, Integer::sum);
        }
        if (!latency.isEmpty()) b.put("latencyMs", median(latency));
        b.put("modelCalls", modelCalls);
        b.put("promptTokens", promptTokens);
        b.put("completionTokens", completionTokens);
        b.set("handlers", json.valueToTree(handlers));
        b.set("failures", json.valueToTree(failures));
        return b;
    }
}
