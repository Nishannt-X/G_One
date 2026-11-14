package com.example.g_one.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.memory.MemoryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Attack harness (privacy review round 2, mandatory): a rich fake call with planted secrets in every field a console
 * event can carry, pushed through the real {@link CallAccumulator}, {@link CallRecordWriter}, a SQLite
 * {@link CallRecordStore} file and the real {@link AnalyticsController}, across the consent matrix, a withheld caller, a
 * denied name check, a forget during an open call, sixty concurrent calls, a failing store and a restart. None of the
 * planted text may appear in the stored row or in the JSON the controller serves. Synthetic data only.
 */
class AnalyticsAttackHarnessTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T0 = 1_794_132_000_000L;

    /** Planted secrets; none may ever be stored or served. */
    static final List<String> PLANTS = List.of(
            "9876543210", "+91 98765 43210", "९८७६५४३२१०", "৯৮৭৬৫৪৩২১০", "௯௮௭௬௫௪௩௨௧௦", "೯೮೭೬೫೪೩೨೧೦",
            "RameshPrivateAddress", "Sunita Devi Kulkarni", "Flat 4B Shanti Nagar Pune", "https://evil.example/x",
            "ramesh@example.org", "IllegalStateException: boom at Ramesh", "Ramesh-Bhasha", "secret-model-Ramesh",
            "timeout-for-Ramesh", "handler-Ramesh", "reason-Ramesh", "buyer-Ramesh-Id", "Ramesh Traders Private",
            "please tell my wife", "call me", "Agmarknet listed");
    /** Tokens the plants are built from: none may appear either. */
    private static final List<String> TOKENS = List.of("Ramesh", "Sunita", "Kulkarni", "Shanti", "evil.example",
            "example.org", "Exception", "Bhasha", "wife", "98765", "९८७६", "৯৮৭৬", "௯௮௭", "೯೮೭", "Agmarknet");
    /** Seven or more digits in any script, with or without separators. */
    private static final Pattern PHONE_LIKE = Pattern.compile("\\d(?:[\\s\\-().+/]*\\d){6,}",
            Pattern.UNICODE_CHARACTER_CLASS);

    private static final class Rig implements AutoCloseable {
        final ConsoleBus bus = new ConsoleBus();
        final CallRecordStore store;
        final CallRecordWriter writer;
        final AnalyticsController controller;

        Rig(Path dir) {
            this(new CallRecordStore("jdbc:sqlite:" + dir.resolve("g-one.db")));
        }

        Rig(CallRecordStore store) {
            this.store = store;
            this.writer = new CallRecordWriter(bus, store, JSON, Clock.systemUTC());
            this.controller = new AnalyticsController(store,
                    new MemoryProperties("sqlite", "./data", "", "", ""), writer);
        }

        /** Everything a reader can get: the stored rows (with the caller key column) and the three endpoints. */
        String everything() throws Exception {
            StringBuilder b = new StringBuilder();
            for (CallRecord r : store.list(1000)) {
                b.append(r).append('\n').append(JSON.writeValueAsString(r)).append('\n');
                b.append(JSON.writeValueAsString(controller.call(r.callId(), null).getBody())).append('\n');
            }
            b.append(JSON.writeValueAsString(controller.calls(500, null).getBody())).append('\n');
            b.append(JSON.writeValueAsString(controller.summary(null).getBody())).append('\n');
            return b.toString();
        }

        @Override
        public void close() {
            writer.close();
            store.close();
        }
    }

    /** One call carrying planted text in every field an event has. */
    static void richCall(ConsoleBus bus, String id, long t, String farmer, String buyer, boolean withheld,
                         boolean denied, boolean end) {
        CallId c = CallId.of(id);
        LegId f = LegId.of(id + "-f");
        LegId b = LegId.of(id + "-b");
        List<ConsoleEvent> e = new ArrayList<>();
        e.add(new ConsoleEvent.CallStarted(c, t, "Ramesh-Bhasha"));
        e.add(new ConsoleEvent.LegJoined(c, t, f, LegRole.FARMER, LegSource.TWILIO, "Ramesh-Bhasha"));
        if (!withheld) {
            e.add(new ConsoleEvent.MemoryLoaded(c, t + 10, FakeLeg.FARMER_KEY, true,
                    List.of(new ProfileFact("name", "Suresh", Provenance.said(CallId.of("old"), t - 5000)),
                            new ProfileFact("village", "call ৯৮৭৬৫৪৩২১০", Provenance.said(CallId.of("old"), t - 5000))),
                    List.of(new CallGist(CallId.of("old"), t - 9000, t - 8000, "Old call, ring ९८७६५४३२१०.",
                            List.of("email ramesh@example.org")))));
        }
        e.add(new ConsoleEvent.LanguageChanged(c, t + 900, f, LegRole.FARMER, "Ramesh-Bhasha", "hi-IN", "reason-Ramesh"));
        e.add(new ConsoleEvent.TurnHeard(c, t + 2000, f, LegRole.FARMER, "hi-IN", "call me 9876543210", t + 1000, t + 1500));
        e.add(new ConsoleEvent.AssistantSaid(c, t + 2600, f, LegRole.FARMER, "hi-IN", "Sunita Devi Kulkarni"));
        e.add(new ConsoleEvent.TurnMeasured(c, t + 2600, "handler-Ramesh", "secret-model-Ramesh", 10, 5, 1, 600,
                "timeout-for-Ramesh"));
        e.add(new ConsoleEvent.ToolCalled(c, t + 2700, "RameshPrivateAddress",
                JSON.createObjectNode().put("x", "Flat 4B Shanti Nagar Pune"), false, "IllegalStateException: boom at Ramesh", 3));
        e.add(new ConsoleEvent.ToolCalled(c, t + 3000, "confirm_name",
                JSON.createObjectNode().put("confirmed", !denied), true, "Sunita Devi Kulkarni", 3));
        e.add(new ConsoleEvent.ToolCalled(c, t + 3100, "save_profile_fact",
                JSON.createObjectNode().put("field", "name").put("value", "Ramesh ९८७६५४३२१०"), true, "ok", 4));
        e.add(new ConsoleEvent.ToolCalled(c, t + 3200, "get_mandi_price",
                JSON.createObjectNode().put("commodity", "+91 98765 43210"), true,
                "Agmarknet listed 2,150; call me 9876543210 or https://evil.example/x", 120));
        e.add(new ConsoleEvent.WhisperSent(c, t + 3300, f, ConsoleEvent.WhisperKind.CAUTION, "hi-IN", "please tell my wife"));
        e.add(new ConsoleEvent.AsideAnswered(c, t + 3400, f, LegRole.FARMER, "hi-IN", "Sunita Devi Kulkarni",
                "ramesh@example.org", t + 3350));
        e.add(new ConsoleEvent.ToolCalled(c, t + 4000, "connect_buyer",
                JSON.createObjectNode().put("buyerId", "buyer-Ramesh-Id"), true, "Calling Ramesh Traders Private now.", 5));
        e.add(new ConsoleEvent.LegJoined(c, t + 5000, b, LegRole.BUYER, LegSource.SIMULATOR, "Ramesh-Bhasha"));
        e.add(new ConsoleEvent.TurnHeard(c, t + 6000, b, LegRole.BUYER, "Ramesh-Bhasha", "+91 98765 43210", t + 5600,
                t + 5800));
        e.add(new ConsoleEvent.TurnTranslated(c, t + 6400, b, LegRole.BUYER, LegRole.FARMER, "Ramesh-Bhasha", "hi-IN",
                "Flat 4B Shanti Nagar Pune", t + 5600));
        e.add(new ConsoleEvent.DealUpdated(c, t + 7000, ConsoleEvent.DealState.AGREED, "Ramesh Traders Private",
                "Sunita Devi Kulkarni", "Flat 4B Shanti Nagar Pune", "९८७६५४३२१० quintal", new BigDecimal("2100"),
                "Ramesh-Bhasha", "please tell my wife; payment ramesh@example.org", true, true));
        e.add(new ConsoleEvent.LegEnded(c, t + 8000, b, LegRole.BUYER, "IllegalStateException: boom at Ramesh"));
        if (farmer != null) e.add(new ConsoleEvent.ConsentRecorded(c, LegRole.FARMER, farmer, t + 8500));
        if (buyer != null) e.add(new ConsoleEvent.ConsentRecorded(c, LegRole.BUYER, buyer, t + 8500));
        e.add(new ConsoleEvent.LegEnded(c, t + 9000, f, LegRole.FARMER, "reason-Ramesh"));
        if (end) e.add(new ConsoleEvent.CallEnded(c, t + 9000, 9000, "IllegalStateException: boom at Ramesh"));
        e.forEach(bus::publish);
    }

    static void assertClean(String everything) {
        for (String p : PLANTS) assertThat(everything).as("planted " + p).doesNotContain(p);
        for (String p : TOKENS) assertThat(everything).as("token " + p).doesNotContain(p);
    }

    /** No string value of the served JSON holds a phone-like run of digits in any script. */
    static void assertNoPhoneLike(Object body) {
        List<String> values = new ArrayList<>();
        collect(JSON.valueToTree(body), values);
        for (String v : values) assertThat(PHONE_LIKE.matcher(v).find()).as("served value " + v).isFalse();
    }

    private static void collect(JsonNode n, List<String> out) {
        if (n.isTextual()) out.add(n.asText());
        n.fieldNames().forEachRemaining(out::add);
        n.forEach(c -> collect(c, out));
    }

    static Stream<Arguments> matrix() {
        List<Arguments> out = new ArrayList<>();
        for (String farmer : new String[] {"YES", "NO", "NONE"}) {
            for (String buyer : new String[] {"YES", "NO", "NONE"}) {
                for (boolean withheld : new boolean[] {false, true}) {
                    for (boolean denied : new boolean[] {false, true}) {
                        out.add(Arguments.of(farmer, buyer, withheld, denied));
                    }
                }
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "farmer={0} buyer={1} withheld={2} denied={3}")
    @MethodSource("matrix")
    void nothingPlantedIsStoredOrServed(String farmer, String buyer, boolean withheld, boolean denied,
                                        @TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            richCall(rig.bus, "call-attack", T0, farmer, buyer, withheld, denied, true);
            assertThat(rig.store.count()).isEqualTo(1);
            assertClean(rig.everything());
            assertNoPhoneLike(rig.controller.call("call-attack", null).getBody());
            JsonNode d = JSON.valueToTree(rig.controller.call("call-attack", null).getBody()).get("data");
            assertThat(d.get("farmer").get("tools")).extracting(t -> t.get("tool").asText()).contains("other");
            assertThat(JSON.valueToTree(rig.controller.call("call-attack", null).getBody()).get("endReason").asText())
                    .isEqualTo("other");
        }
    }

    @Test
    void aForgetDuringAnOpenCallLeavesNothingPlantedOrLinked(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            richCall(rig.bus, "call-open", T0, "YES", "YES", false, false, false);
            rig.writer.forgetNow(FakeLeg.FARMER_KEY.value());
            rig.bus.publish(new ConsoleEvent.CallEnded(CallId.of("call-open"), T0 + 9000, 9000, "hangup"));
            String all = rig.everything();
            assertClean(all);
            assertThat(all).doesNotContain(FakeLeg.FARMER_KEY.value()).doesNotContain("Suresh");
        }
    }

    @Test
    void sixtyConcurrentRichCallsAreAllRecordedAndClean(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            for (int i = 0; i < 60; i++) {
                richCall(rig.bus, "call-" + i, T0 + i * 10_000L, "NO", "NO", false, false, false);
            }
            for (int i = 0; i < 60; i++) {
                rig.bus.publish(new ConsoleEvent.CallEnded(CallId.of("call-" + i), T0 + i * 10_000L + 9000, 9000, "hangup"));
            }
            assertThat(rig.store.count()).isEqualTo(60);
            assertClean(rig.everything());
        }
    }

    @Test
    void aFailingStoreLosesNothingAndLeaksNothing(@TempDir Path dir) throws Exception {
        AtomicBoolean down = new AtomicBoolean(true);
        CallRecordStore flaky = new CallRecordStore("jdbc:sqlite:" + dir.resolve("g-one.db")) {
            @Override
            public synchronized void save(CallRecord record) {
                if (down.get()) throw new IllegalStateException("synthetic outage");
                super.save(record);
            }
        };
        try (Rig rig = new Rig(flaky)) {
            for (int i = 0; i < 5; i++) richCall(rig.bus, "call-" + i, T0 + i * 10_000L, "YES", "NO", false, false, true);
            assertThat(flaky.count()).isZero();
            down.set(false);
            richCall(rig.bus, "call-after", T0 + 100_000, "NO", "NO", false, false, true);
            assertThat(flaky.count()).isEqualTo(6);
            assertClean(rig.everything());
        }
    }

    @Test
    void afterARestartTheRowsAreStillCleanAndServed(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            richCall(rig.bus, "call-1", T0, "YES", "YES", false, false, true);
            richCall(rig.bus, "call-2", T0 + 20_000, "NONE", "NO", true, true, true);
        }
        try (Rig rig = new Rig(dir)) {
            assertThat(rig.store.count()).isEqualTo(2);
            assertThat(rig.controller.calls(10, null).getStatusCode().value()).isEqualTo(200);
            assertClean(rig.everything());
        }
    }
}
