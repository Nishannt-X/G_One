package com.example.g_one.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.agent.language.LanguageLines;
import com.example.g_one.agent.pipeline.CallEndHandler.EndedCall;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallId;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.fakes.FakeLeg;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Which ended calls write a gist: none for aborted, near-empty or unconfirmed calls. Synthetic data only. */
class GistOnHangupTest {
    private static final long START = 1_762_590_000_000L;
    private static final CallSummarizer OFF = new CallSummarizer() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Draft summarize(CallTranscript transcript, FarmerMemory current) {
            throw new IllegalStateException("off");
        }
    };

    private final MemoryStore store = new InMemoryMemoryStore();
    private final GistWriter writer = new GistWriter(store, OFF);
    private final List<CallId> released = new ArrayList<>();

    @AfterEach
    void tearDown() {
        writer.close();
    }

    private void hangUp(EndedCall call, MemoryGate.State state) {
        new GistOnHangup(writer, id -> Optional.ofNullable(state), released::add).callEnded(call);
    }

    private static EndedCall call(long durationMs, String dealSummary, String... farmerLines) {
        return call(FarmerMemory.empty(FakeLeg.FARMER_KEY), durationMs, dealSummary, farmerLines);
    }

    private static EndedCall call(FarmerMemory memory, long durationMs, String dealSummary, String... farmerLines) {
        CallId id = CallId.random();
        FakeLeg leg = new FakeLeg(id, LegRole.FARMER, LegSource.SIMULATOR, FakeLeg.HINDI, FakeLeg.FARMER_KEY);
        CallContext ctx = new CallContext(id, leg, FakeLeg.HINDI, memory);
        List<AgentBrain.Line> lines = new ArrayList<>();
        lines.add(new AgentBrain.Line(AgentBrain.Speaker.ASSISTANT, "नमस्ते, जी-वन में आपका स्वागत है।"));
        for (String l : farmerLines) {
            lines.add(new AgentBrain.Line(AgentBrain.Speaker.FARMER, l));
            lines.add(new AgentBrain.Line(AgentBrain.Speaker.ASSISTANT, "ठीक है।"));
        }
        return new EndedCall(ctx, lines, START, START + durationMs, "hangup",
                dealSummary == null ? null : "deal_confirmed", dealSummary);
    }

    private boolean gistWritten() {
        return store.load(FakeLeg.FARMER_KEY).map(m -> !m.gists().isEmpty()).orElse(false);
    }

    @Test
    void aFiveSecondAbortedCallWritesNoGist() {
        EndedCall aborted = call(5_000, null, "प्याज़ का भाव", "हाँ");
        hangUp(aborted, MemoryGate.State.NEW);
        assertThat(gistWritten()).isFalse();
        assertThat(released).containsExactly(aborted.callId());
    }

    @Test
    void aCallWithOneFarmerTurnWritesNoGist() {
        hangUp(call(60_000, null, "हैलो"), MemoryGate.State.NEW);
        assertThat(gistWritten()).isFalse();
    }

    @Test
    void anUnconfirmedOrFailedNameCheckWritesNoGist() {
        hangUp(call(60_000, null, "कौन?", "प्याज़ का भाव बताइए"), MemoryGate.State.NAME_CHECK);
        hangUp(call(60_000, null, "नहीं, मैं रमेश हूँ", "प्याज़ का भाव बताइए"), MemoryGate.State.OTHER_SPEAKER);
        assertThat(gistWritten()).isFalse();
    }

    @Test
    void anUnknownForgottenOrWithheldCallWritesNoGist() {
        hangUp(call(60_000, null, "प्याज़ का भाव", "ठीक है"), null);
        hangUp(call(60_000, null, "प्याज़ का भाव", "मुझे भूल जाओ"), MemoryGate.State.FORGOTTEN);
        assertThat(gistWritten()).isFalse();
    }

    @Test
    void aRealCallWritesAGistAndAShortConfirmedDealStillDoes() {
        hangUp(call(60_000, null, "प्याज़ का भाव बताइए", "धन्यवाद"), MemoryGate.State.CONFIRMED);
        assertThat(store.load(FakeLeg.FARMER_KEY).orElseThrow().gists()).hasSize(1);

        hangUp(call(10_000, "Deal summary confirmed: 20 quintal onion.", "हाँ"), MemoryGate.State.NEW);
        assertThat(store.load(FakeLeg.FARMER_KEY).orElseThrow().gists()).hasSize(2);
    }

    @Test
    void anUnnamedCallerWithAnExistingProfileWritesNoGistAndNeverReachesTheSummarizer() {
        java.util.concurrent.atomic.AtomicInteger summarized = new java.util.concurrent.atomic.AtomicInteger();
        CallSummarizer counting = new CallSummarizer() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Draft summarize(CallTranscript transcript, FarmerMemory current) {
                summarized.incrementAndGet();
                throw new IllegalStateException("must not be called");
            }
        };
        MemoryStore own = new InMemoryMemoryStore();
        own.upsertFacts(FakeLeg.FARMER_KEY, List.of(new com.example.g_one.core.ProfileFact("district", "Nashik",
                com.example.g_one.core.Provenance.said(CallId.random(), START))));
        FarmerMemory existing = own.load(FakeLeg.FARMER_KEY).orElseThrow();
        try (GistWriter w = new GistWriter(own, counting)) {
            new GistOnHangup(w, id -> Optional.of(MemoryGate.State.UNNAMED), released::add)
                    .callEnded(call(existing, 60_000, "Deal summary confirmed: 20 quintal onion.", "प्याज़ बेचना है", "हाँ"));
        }
        assertThat(summarized).hasValue(0);
        assertThat(own.load(FakeLeg.FARMER_KEY).orElseThrow().gists()).isEmpty();
    }

    @Test
    void namingTheLanguageIsCallSetupAndDoesNotCountAsAFarmerTurn() {
        CallId id = CallId.random();
        FakeLeg leg = new FakeLeg(id, LegRole.FARMER, LegSource.SIMULATOR, FakeLeg.HINDI, FakeLeg.FARMER_KEY);
        CallContext ctx = new CallContext(id, leg, FakeLeg.HINDI, FarmerMemory.empty(FakeLeg.FARMER_KEY));
        List<AgentBrain.Line> setupOnly = List.of(
                new AgentBrain.Line(AgentBrain.Speaker.ASSISTANT, LanguageLines.opener(LegRole.FARMER)),
                new AgentBrain.Line(AgentBrain.Speaker.FARMER, "umm"),
                new AgentBrain.Line(AgentBrain.Speaker.ASSISTANT, LanguageLines.reask()),
                new AgentBrain.Line(AgentBrain.Speaker.FARMER, "हिंदी"),
                new AgentBrain.Line(AgentBrain.Speaker.ASSISTANT, LanguageLines.confirmation("hi-IN")),
                new AgentBrain.Line(AgentBrain.Speaker.ASSISTANT, "नमस्ते, जी-वन में आपका स्वागत है।"),
                new AgentBrain.Line(AgentBrain.Speaker.FARMER, "प्याज़ का भाव"));
        EndedCall setup = new EndedCall(ctx, setupOnly, START, START + 60_000, "hangup", null, null);
        assertThat(GistOnHangup.farmerTurns(setup)).isEqualTo(1);
        hangUp(setup, MemoryGate.State.NEW);
        assertThat(gistWritten()).as("two setup answers and one real turn are a near-empty call").isFalse();

        List<AgentBrain.Line> withContent = List.of(
                new AgentBrain.Line(AgentBrain.Speaker.ASSISTANT, LanguageLines.opener(LegRole.FARMER)),
                new AgentBrain.Line(AgentBrain.Speaker.FARMER, "हिंदी में, मुझे प्याज़ का भाव बताइए"),
                new AgentBrain.Line(AgentBrain.Speaker.ASSISTANT, LanguageLines.confirmation("hi-IN")),
                new AgentBrain.Line(AgentBrain.Speaker.FARMER, "बीस क्विंटल है"));
        EndedCall real = new EndedCall(ctx, withContent, START, START + 60_000, "hangup", null, null);
        assertThat(GistOnHangup.farmerTurns(real)).as("a language answer that also asks something counts").isEqualTo(2);
        hangUp(real, MemoryGate.State.NEW);
        assertThat(gistWritten()).isTrue();
    }

    private static EndedCall afterTheLanguageQuestion(String... farmerTurns) {
        CallId id = CallId.random();
        FakeLeg leg = new FakeLeg(id, LegRole.FARMER, LegSource.SIMULATOR, FakeLeg.HINDI, FakeLeg.FARMER_KEY);
        CallContext ctx = new CallContext(id, leg, FakeLeg.HINDI, FarmerMemory.empty(FakeLeg.FARMER_KEY));
        List<AgentBrain.Line> lines = new ArrayList<>();
        lines.add(new AgentBrain.Line(AgentBrain.Speaker.ASSISTANT, LanguageLines.opener(LegRole.FARMER)));
        lines.add(new AgentBrain.Line(AgentBrain.Speaker.FARMER, farmerTurns[0]));
        lines.add(new AgentBrain.Line(AgentBrain.Speaker.ASSISTANT, LanguageLines.confirmation("hi-IN")));
        for (int i = 1; i < farmerTurns.length; i++) {
            lines.add(new AgentBrain.Line(AgentBrain.Speaker.ASSISTANT, "ठीक है।"));
            lines.add(new AgentBrain.Line(AgentBrain.Speaker.FARMER, farmerTurns[i]));
        }
        return new EndedCall(ctx, lines, START, START + 60_000, "hangup", null, null);
    }

    @Test
    void aShortRequestGivenWithTheLanguageCountsAsARealTurn() {
        assertThat(GistOnHangup.farmerTurns(afterTheLanguageQuestion("Hindi wheat price", "बीस क्विंटल है"))).isEqualTo(2);
        hangUp(afterTheLanguageQuestion("Hindi wheat price", "बीस क्विंटल है"), MemoryGate.State.NEW);
        assertThat(gistWritten()).isTrue();
    }

    @Test
    void onlyNamingTheLanguageNeverMakesACallWorthAGist() {
        assertThat(GistOnHangup.farmerTurns(afterTheLanguageQuestion("Hindi"))).isZero();
        assertThat(GistOnHangup.farmerTurns(afterTheLanguageQuestion("English please"))).isZero();
        hangUp(afterTheLanguageQuestion("हिंदी", "ठीक है"), MemoryGate.State.NEW);
        assertThat(gistWritten()).isFalse();
    }

    @Test
    void theActionTableFollowsTheMemoryState() {
        EndedCall real = call(60_000, null, "प्याज़ का भाव बताइए", "धन्यवाद");
        // UNNAMED with no stored memory is a first-time caller in all but name: the gist is still written.
        assertThat(GistOnHangup.action(real, Optional.of(MemoryGate.State.UNNAMED))).isEqualTo(GistOnHangup.Action.WRITE);
        assertThat(GistOnHangup.action(real, Optional.of(MemoryGate.State.WITHHELD))).isEqualTo(GistOnHangup.Action.SKIP);
        assertThat(GistOnHangup.action(real, Optional.empty())).isEqualTo(GistOnHangup.Action.SKIP);
    }
}
