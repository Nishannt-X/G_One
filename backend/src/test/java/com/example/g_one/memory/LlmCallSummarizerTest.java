package com.example.g_one.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallId;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.fakes.FakeLeg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The summarizer request stays bounded for a long call, and asks for a pickup follow-up after a deal. */
class LlmCallSummarizerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long START = 1_762_590_000_000L;
    // userMessage never calls the model, so no client is needed.
    private final LlmCallSummarizer summarizer = new LlmCallSummarizer(null, JSON);

    private static CallTranscript transcript(int lines, int charsEach) {
        List<CallTranscript.Line> out = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            String text = ("line " + i + " ").repeat(Math.max(1, charsEach / 8));
            out.add(new CallTranscript.Line(i % 2 == 0 ? CallTranscript.Speaker.FARMER : CallTranscript.Speaker.ASSISTANT,
                    text, START + i));
        }
        out.add(CallTranscript.Line.assistant("Deal summary confirmed: 20 quintal onion.", START + lines));
        return new CallTranscript(CallId.of("c-long"), START, START + 600_000, out, false, Optional.empty());
    }

    private JsonNode request(CallTranscript t) throws Exception {
        return JSON.readTree(summarizer.userMessage(t, FarmerMemory.empty(FakeLeg.FARMER_KEY)));
    }

    @Test
    void aLongCallSendsOnlyItsNewestFortyLines() throws Exception {
        JsonNode req = request(transcript(120, 20));

        assertThat(req.path("transcript")).hasSize(LlmCallSummarizer.MAX_LINES);
        assertThat(req.path("transcriptOmitted").asInt()).isEqualTo(121 - LlmCallSummarizer.MAX_LINES);
        JsonNode lines = req.path("transcript");
        assertThat(lines.get(lines.size() - 1).path("text").asText()).startsWith("Deal summary confirmed");
    }

    @Test
    void longLinesAreCappedByCharactersToo() throws Exception {
        JsonNode req = request(transcript(30, 800));

        int chars = 0;
        for (JsonNode l : req.path("transcript")) chars += l.path("text").asText().length();
        assertThat(chars).isLessThanOrEqualTo(LlmCallSummarizer.MAX_CHARS);
        assertThat(req.path("transcript").size()).isLessThan(31);
        assertThat(req.path("transcriptOmitted").asInt()).isPositive();
    }

    @Test
    void aShortCallIsSentWhole() throws Exception {
        JsonNode req = request(transcript(6, 20));
        assertThat(req.path("transcript")).hasSize(7);
        assertThat(req.has("transcriptOmitted")).isFalse();
    }

    @Test
    void anOverlongSingleLineKeepsItsEnd() {
        List<CallTranscript.Line> one = List.of(CallTranscript.Line.assistant("x".repeat(9_000) + "END", START));
        List<CallTranscript.Line> kept = LlmCallSummarizer.capped(one);
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).text()).hasSize(LlmCallSummarizer.MAX_CHARS).endsWith("END");
    }

    @Test
    void thePromptAsksForAPickupFollowUpAfterAConfirmedDeal() {
        assertThat(LlmCallSummarizer.SYSTEM).contains("check the pickup and payment").contains("pickup day as dueHint");
    }
}
