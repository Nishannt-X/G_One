package com.example.g_one.memory;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.agent.brain.LlmAgentBrain;
import com.example.g_one.agent.pipeline.CallEndHandler;
import com.example.g_one.agent.tools.memory.MemoryGate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Writes the call gist at hang-up and releases the brain's per-call state. The confirmed deal summary goes in as a line
 * of the transcript, so the summarizer sees it and the template gist can quote it when the summarizer is off.
 */
@Component
@Order(100)
public class GistOnHangup implements CallEndHandler {
    private static final Logger log = LoggerFactory.getLogger(GistOnHangup.class);

    private final GistWriter writer;
    private final LlmAgentBrain brain;

    public GistOnHangup(GistWriter writer, LlmAgentBrain brain) {
        this.writer = writer;
        this.brain = brain;
    }

    @Override
    public void callEnded(EndedCall call) {
        Optional<MemoryGate.State> state = brain.memoryState(call.callId());
        try {
            if (call.callerKey().isEmpty() || state.filter(s -> s == MemoryGate.State.FORGOTTEN).isPresent()) return;
            writer.write(call.context().memory(), transcript(call,
                    state.filter(s -> s == MemoryGate.State.OTHER_SPEAKER).isPresent()));
        } catch (RuntimeException e) {
            log.warn("Writing the gist failed for call {} ({})", call.callId(), e.getClass().getSimpleName());
        } finally {
            brain.callEnded(call.callId());
        }
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
