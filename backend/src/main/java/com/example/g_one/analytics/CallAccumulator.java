package com.example.g_one.analytics;

import com.example.g_one.agent.tools.buyers.ConnectBuyerTool;
import com.example.g_one.agent.tools.buyers.FindBuyersTool;
import com.example.g_one.agent.tools.mandi.MandiPriceTool;
import com.example.g_one.agent.tools.memory.ConfirmNameTool;
import com.example.g_one.agent.tools.memory.ForgetMeTool;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects what a stored record may need while one call runs, then decides at the end what to keep.
 *
 * <p>Only derived data is collected: no turn, translation, reply or whisper text, no audio, no phone number and no
 * tool arguments. Arguments are read while the call runs only to tell a confirmed name from a denied one and to know
 * which directory buyer was connected; they are never kept.
 *
 * <p>Which party's YES unlocks which field (schema version {@value CallRecord#SCHEMA_VERSION}):
 * <table>
 *   <caption>Consent per field</caption>
 *   <tr><th>Field</th><th>Kept when</th></tr>
 *   <tr><td>times, duration, end reason, outcome</td><td>at least one YES (any record)</td></tr>
 *   <tr><td>{@code deal}: crop, quantity, price, mandi, pickup and payment, per-party yes, state times</td>
 *       <td>at least one YES: both parties heard the same terms</td></tr>
 *   <tr><td>{@code price}: the latest mandi price reference (market data, never a party's words)</td>
 *       <td>at least one YES</td></tr>
 *   <tr><td>{@code farmer}: language and switches, leg times, turn timings and medians, tools used (name, result
 *       for price lookups only, duration, time), private advice (kind and time only), identity check</td>
 *       <td>farmer YES, with a caller ID, and no "forget me" on the call</td></tr>
 *   <tr><td>{@code farmer.memory}: what was remembered when the call began (facts, newest gists)</td>
 *       <td>as {@code farmer}, and the caller did not deny being the remembered farmer</td></tr>
 *   <tr><td>{@code buyer}: language and switches, leg times, turn timings and medians, the directory buyer that was
 *       connected (synthetic id and name)</td><td>buyer YES, and a buyer leg joined</td></tr>
 * </table>
 * When no side is kept there is no record at all.
 */
final class CallAccumulator {
    /** Tool results kept: price lookups only, which hold market data, never a party's words. */
    private static final String PRICE_TOOL = MandiPriceTool.NAME;
    /** Lookups that make a call without terms "information only". */
    private static final Set<String> INFO_TOOLS = Set.of(MandiPriceTool.NAME, WeatherTool.NAME, FindBuyersTool.NAME);
    /** Newest remembered gists kept with the memory snapshot: the last call and the open follow-ups. */
    static final int MAX_GISTS = 3;
    private static final Pattern CALLING = Pattern.compile("^Calling (.{1,80}?) now\\.");

    private final CallId callId;
    private final long startedMs;
    private final String startLanguage;
    private final Map<LegRole, Side> sides = new EnumMap<>(LegRole.class);
    private final Map<LegId, LegRole> legRoles = new HashMap<>();
    private final Map<LegRole, Consent> consents = new EnumMap<>(LegRole.class);
    private final List<ConsoleEvent.ToolCalled> tools = new ArrayList<>();
    private final List<ConsoleEvent.WhisperSent> whispers = new ArrayList<>();
    private final Map<String, Long> dealStates = new LinkedHashMap<>();
    private ConsoleEvent.MemoryLoaded memory;
    private ConsoleEvent.DealUpdated deal;
    private ConsoleEvent.CallEnded ended;
    private ConsoleEvent.ToolCalled priceReference;
    private Long farmerYesMs;
    private Long buyerYesMs;
    private boolean forgot;
    /** "confirmed", "denied" or "unchecked": the returning farmer's name check. */
    private String identity = "unchecked";
    private String buyerId;
    private String buyerName;
    private String farmerLanguage;

    /** One party's leg. Timings are keyed like the console's: leg id and the turn's start time. */
    private static final class Side {
        String legId;
        String source;
        String language;
        String firstLanguage;
        long joinedMs;
        Long endedMs;
        String endReason;
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
                ObjectNode t = json.createObjectNode();
                t.put("key", e.legId().value() + ":" + e.startedMs());
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
            case ConsoleEvent.TurnMeasured e -> { }
        }
    }

    private void onTool(ConsoleEvent.ToolCalled e) {
        tools.add(e);
        if (!e.ok()) {
            return;
        }
        switch (e.tool()) {
            case ForgetMeTool.NAME -> forgot = true;
            case PRICE_TOOL -> priceReference = e;
            case ConfirmNameTool.NAME -> {
                JsonNode confirmed = e.args() == null ? null : e.args().get("confirmed");
                if (confirmed != null && confirmed.isBoolean()) {
                    identity = confirmed.asBoolean() ? "confirmed" : "denied";
                }
            }
            case ConnectBuyerTool.NAME -> {
                JsonNode id = e.args() == null ? null : e.args().get("buyerId");
                Matcher m = CALLING.matcher(e.result() == null ? "" : e.result());
                if (id != null && id.isTextual() && id.asText().length() <= 64) buyerId = id.asText();
                if (m.find()) buyerName = m.group(1);
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
        } else if (!buyerJoined()) {
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
        data.set("price", priceJson(json));
        ObjectNode notStored = data.putObject("notStored");
        if (farmerWhy != null) notStored.put("farmer", farmerWhy);
        if (buyerWhy != null) notStored.put("buyer", buyerWhy);
        if (keepFarmer && identity.equals("denied")) notStored.put("memory", "not_this_farmer");
        data.set("farmer", keepFarmer ? farmerJson(json) : null);
        data.set("buyer", keepBuyer ? buyerJson(json) : null);

        Map<String, String> languages = new LinkedHashMap<>();
        if (keepFarmer) languages.put("farmer", farmerLanguageNow());
        if (keepBuyer && sides.get(LegRole.BUYER).language != null) {
            languages.put("buyer", sides.get(LegRole.BUYER).language);
        }
        return Optional.of(new CallRecord(callId.value(), startedMs, endedMs, durationMs,
                ended != null ? ended.reason() : "", languages, outcome(), consent(LegRole.FARMER),
                consent(LegRole.BUYER), keepFarmer ? memory.callerKey().value() : null, data, nowMs,
                CallRecord.SCHEMA_VERSION));
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
        ObjectNode states = d.putObject("stateTimes");
        dealStates.forEach(states::put);
        d.put("readback", dealStates.containsKey("readback") || dealStates.containsKey("confirming")
                || dealStates.containsKey("agreed"));
        if (farmerYesMs != null) d.put("farmerYesMs", farmerYesMs);
        if (buyerYesMs != null) d.put("buyerYesMs", buyerYesMs);
        return d;
    }

    /** The newest successful mandi price lookup: market data, kept with any record for the price comparison. */
    private ObjectNode priceJson(ObjectMapper json) {
        if (priceReference == null) {
            return null;
        }
        ObjectNode p = json.createObjectNode();
        p.put("reference", priceReference.result());
        p.put("atMs", priceReference.atMs());
        return p;
    }

    private ObjectNode sideJson(ObjectMapper json, Side side) {
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
        ArrayNode timings = s.putArray("timings");
        side.timings.values().forEach(timings::add);
        s.set("latency", latencyJson(json, side.timings.values()));
        return s;
    }

    /** Medians per stage in milliseconds, worked out like the console's: absent when nothing was measured. */
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

    private static void putMedian(ObjectNode out, String key, List<Long> values) {
        if (values.isEmpty()) {
            return;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        out.put(key, sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2);
    }

    private ObjectNode buyerJson(ObjectMapper json) {
        ObjectNode b = sideJson(json, sides.get(LegRole.BUYER));
        if (buyerId != null || buyerName != null) {
            ObjectNode m = b.putObject("match");
            m.put("buyerId", buyerId);
            m.put("name", buyerName);
        }
        return b;
    }

    private ObjectNode farmerJson(ObjectMapper json) {
        Side side = sides.get(LegRole.FARMER);
        ObjectNode f = side != null ? sideJson(json, side) : json.createObjectNode();
        f.put("language", farmerLanguageNow());
        if (side == null || side.firstLanguage == null) f.put("firstLanguage", startLanguage);
        f.put("identity", identity);
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
        if (identity.equals("denied")) {
            // The caller said they are not the remembered farmer: that memory is not theirs to attach to this call.
            f.putNull("memory");
        } else {
            ObjectNode m = f.putObject("memory");
            m.put("returning", memory.returning());
            m.set("facts", json.valueToTree(memory.facts()));
            List<CallGist> newest = memory.gists().stream()
                    .sorted(Comparator.comparingLong(CallGist::startedMs).reversed()).limit(MAX_GISTS).toList();
            m.set("gists", json.valueToTree(newest));
        }
        return f;
    }
}
