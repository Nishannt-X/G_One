package com.example.g_one.memory;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.agent.brain.LlmAgentBrain;
import com.example.g_one.agent.language.LanguageDetector;
import com.example.g_one.agent.language.LanguageLines;
import com.example.g_one.agent.pipeline.CallEndHandler;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.core.CallId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Writes the call gist at hang-up and releases the brain's per-call state. The confirmed deal summary goes in as a line
 * of the transcript, so the summarizer sees it and the template gist can quote it when the summarizer is off.
 *
 * <p>No gist is written for a call that would only add noise to the farmer's memory (see {@link #action}): one the
 * brain never saw (fail closed: its state may have been forgotten), one whose name check failed or was never answered,
 * an unnamed caller whose number already has a profile (identity was never established, so their history is neither
 * extended nor sent to the summarizer), and a near-empty call (fewer than {@value #MIN_FARMER_TURNS} farmer turns or shorter than {@value #MIN_CALL_MS} ms)
 * without a confirmed deal. A junk gist would otherwise become the newest one and drive the next call's recall.
 */
@Component
@Order(100)
public class GistOnHangup implements CallEndHandler {
    /** A call with fewer farmer turns than this writes no gist (unless a deal was confirmed). */
    static final int MIN_FARMER_TURNS = 2;
    /** A call shorter than this writes no gist (unless a deal was confirmed). */
    static final long MIN_CALL_MS = 15_000;
    private static final Logger log = LoggerFactory.getLogger(GistOnHangup.class);

    /** What hang-up does with memory. */
    enum Action { WRITE, SKIP }

    private final GistWriter writer;
    private final Function<CallId, Optional<MemoryGate.State>> memoryState;
    private final Consumer<CallId> dropState;

    @Autowired
    public GistOnHangup(GistWriter writer, LlmAgentBrain brain) {
        this(writer, brain::memoryState, brain::callEnded);
    }

    /** For tests: the brain's per-call memory state and its release, without a whole brain. */
    GistOnHangup(GistWriter writer, Function<CallId, Optional<MemoryGate.State>> memoryState,
            Consumer<CallId> dropState) {
        this.writer = writer;
        this.memoryState = memoryState;
        this.dropState = dropState;
    }

    @Override
    public void callEnded(EndedCall call) {
        Optional<MemoryGate.State> state = memoryState.apply(call.callId());
        try {
            Action action = action(call, state);
            if (action == Action.SKIP) {
                log.info("No gist written for call {} (memory state {}, {} farmer turns, {} ms)", call.callId(),
                        state.map(Enum::name).orElse("unknown"), farmerTurns(call), duration(call));
                return;
            }
            long started = System.nanoTime();
            GistWriter.Result result = writer.write(call.context().memory(), transcript(call, false));
            log.info("Gist written in {} ms for call {} ({}, {} source, {} profile facts)",
                    (System.nanoTime() - started) / 1_000_000, call.callId(), result.status(), result.source(),
                    result.facts().size());
        } catch (RuntimeException e) {
            log.warn("Writing the gist failed for call {} ({})", call.callId(), e.getClass().getSimpleName());
        } finally {
            dropState.accept(call.callId());
        }
    }

    /**
     * Writes only for a new or confirmed farmer, on a call with something worth remembering. Skips a withheld caller ID,
     * an unknown state (fail closed), a forgotten farmer, a name check that failed or was never answered, an unnamed
     * caller with an existing profile (unconfirmed), and a near-empty call without a confirmed deal.
     */
    static Action action(EndedCall call, Optional<MemoryGate.State> state) {
        if (call.callerKey().isEmpty() || call.context().memory().isWithheld() || state.isEmpty()) return Action.SKIP;
        boolean keep = switch (state.get()) {
            case NEW, CONFIRMED -> true;
            // Memory exists but has no name to check it with: whoever is calling was never confirmed as its owner.
            case UNNAMED -> call.context().memory().isEmpty();
            case WITHHELD, FORGOTTEN, NAME_CHECK, OTHER_SPEAKER -> false;
        };
        if (!keep) return Action.SKIP;
        if (call.dealSummary() != null) return Action.WRITE;
        return farmerTurns(call) < MIN_FARMER_TURNS || duration(call) < MIN_CALL_MS ? Action.SKIP : Action.WRITE;
    }

    /**
     * The farmer turns that said something. An answer to the language question that only names a language (or could
     * not be understood) is call setup, not content, so it does not count towards {@value #MIN_FARMER_TURNS}.
     */
    static long farmerTurns(EndedCall call) {
        long turns = 0;
        String asked = null;
        for (AgentBrain.Line l : call.transcript()) {
            if (l.speaker() == AgentBrain.Speaker.ASSISTANT) {
                asked = l.text();
            } else if (l.speaker() == AgentBrain.Speaker.FARMER && l.text() != null && !l.text().isBlank()
                    && !(LanguageLines.asksLanguage(asked) && !LanguageDetector.saysMoreThanALanguage(l.text()))) {
                turns++;
            }
        }
        return turns;
    }

    private static long duration(EndedCall call) {
        return Math.max(0, call.endedMs() - call.startedMs());
    }

    static CallTranscript transcript(EndedCall call, boolean otherSpeaker) {
        List<CallTranscript.Line> lines = new ArrayList<>();
        long at = call.startedMs();
        for (AgentBrain.Line l : call.transcript()) {
            CallTranscript.Speaker speaker = switch (l.speaker()) {
                case FARMER -> CallTranscript.Speaker.FARMER;
                case BUYER -> CallTranscript.Speaker.BUYER;
                case ASSISTANT -> CallTranscript.Speaker.ASSISTANT;
            };
            lines.add(new CallTranscript.Line(speaker, l.text(), at++));
        }
        if (call.dealSummary() != null) lines.add(CallTranscript.Line.assistant(call.dealSummary(), at));
        CallTranscript t = new CallTranscript(call.callId(), call.startedMs(), Math.max(call.endedMs(), call.startedMs()),
                lines, otherSpeaker, Optional.empty());
        return switch (call.outcome() == null ? "" : call.outcome()) {
            case "deal_confirmed" -> t.withOutcome(CallTranscript.Outcome.DEAL_CONFIRMED);
            case "buyer_declined" -> t.withOutcome(CallTranscript.Outcome.BUYER_DECLINED);
            default -> t.withOutcome(call.transcript().stream().anyMatch(l -> l.speaker() == AgentBrain.Speaker.BUYER)
                    ? CallTranscript.Outcome.DEAL_UNCONFIRMED : CallTranscript.Outcome.INFO_ONLY);
        };
    }
}
