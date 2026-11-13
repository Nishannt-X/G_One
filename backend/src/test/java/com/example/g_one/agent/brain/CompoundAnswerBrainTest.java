package com.example.g_one.agent.brain;

import static com.example.g_one.agent.brain.BrainFixture.HINDI;
import static com.example.g_one.agent.brain.BrainFixture.said;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.AgentBrain.Line;
import com.example.g_one.agent.AgentBrain.Speaker;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.core.CallContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The brain's yes or no questions answered with something else in the same turn: "shall I call Mehta Agro?" and the
 * returning farmer's name check. The answer is acted on, and the rest of the turn still reaches the model, in the one
 * model call an ordinary turn costs. Fake model only.
 */
class CompoundAnswerBrainTest {
    private static final String OFFER = "Mehta Agro Traders pyaz ke liye 4100 se 4700 rupaye de rahe hain. "
            + "Kya main unhe abhi call karun?";

    private BrainFixture f;
    private CallContext ctx;

    @BeforeEach
    void start() throws Exception {
        f = new BrainFixture(true, true);
        ctx = BrainFixture.newFarmer(HINDI);
        // The buyer was suggested on an earlier turn, and G-One asked whether to call them.
        f.consent.heard(ctx.callId(), "pyaz bechna hai");
        f.consent.suggested(ctx.callId(), List.of("mehta-agro"), "Onion", "20 quintal", "Nashik");
    }

    @AfterEach
    void stop() {
        f.close();
    }

    private List<Line> asked() {
        return List.of(new Line(Speaker.FARMER, "pyaz bechna hai"), new Line(Speaker.ASSISTANT, OFFER));
    }

    /** Requests made by the small yes/no reader, never by the conversation. */
    private long classifierCalls() {
        return f.llm.requests().stream().map(r -> r.path("messages").get(0).path("content").asText())
                .filter(c -> c.startsWith("You read a phone caller") || c.startsWith("A phone caller was asked")).count();
    }

    private String systemPrompt(int request) {
        JsonNode r = f.llm.requests().get(request);
        return r.path("messages").get(0).path("content").asText();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "haan, but unka naam kya hai?", "yes, and what is the buyer's name", "हाँ, लेकिन उनका नाम क्या है?",
            "হ্যাঁ, কিন্তু ওনার নাম কী?", "ஆமா, ஆனா அவர் பெயர் என்ன?", "ಹೌದು, ಆದರೆ ಅವರ ಹೆಸರು ಏನು?",
            "haan ji, wo kab aayenge?", "phone laga do, unka naam kya hai?"})
    void aYesWithAFollowUpQuestionConnectsAndTheQuestionReachesTheModel(String turn) {
        f.llm.replyToolCall("connect_buyer", "{\"buyerId\":\"mehta-agro\"}");
        f.llm.replyText("Mehta Agro Traders ko call kar rahi hoon, line par bane rahiye.");

        f.brain.respond(ctx, asked(), said(ctx, turn));

        assertThat(f.connected).as("the yes was acted on").hasSize(1);
        assertThat(f.llm.requests().get(0).path("messages")).as("the whole turn, question and all, reached the model")
                .anySatisfy(m -> assertThat(m.path("content").asText()).isEqualTo(turn));
        assertThat(systemPrompt(0)).contains(LlmAgentBrain.CONNECT_YES_AND_MORE);
    }

    @Test
    void aCompoundYesCostsNoMoreModelCallsThanAPlainYes() throws Exception {
        f.llm.replyToolCall("connect_buyer", "{\"buyerId\":\"mehta-agro\"}");
        f.llm.replyText("Call kar rahi hoon.");
        f.brain.respond(ctx, asked(), said(ctx, "haan"));
        int plain = f.llm.requests().size();
        assertThat(f.connected).hasSize(1);

        try (BrainFixture g = new BrainFixture(true, true)) {
            g.consent.heard(ctx.callId(), "pyaz bechna hai");
            g.consent.suggested(ctx.callId(), List.of("mehta-agro"), "Onion", "20 quintal", "Nashik");
            g.llm.replyToolCall("connect_buyer", "{\"buyerId\":\"mehta-agro\"}");
            g.llm.replyText("Call kar rahi hoon, unka naam Mehta Agro Traders hai.");
            g.brain.respond(ctx, asked(), said(ctx, "haan, but unka naam kya hai?"));
            assertThat(g.connected).hasSize(1);
            assertThat(g.llm.requests()).hasSize(plain);
        }
    }

    @Test
    void aQuestionInsteadOfAnAnswerIsAnsweredAndTheCallQuestionAskedOnceMoreOnly() {
        f.llm.replyText("वो नासिक के व्यापारी हैं। क्या मैं उन्हें अभी कॉल करूँ?");
        f.brain.respond(ctx, asked(), said(ctx, "woh kahan ke hain?"));
        assertThat(f.connected).isEmpty();
        assertThat(systemPrompt(0)).contains(LlmAgentBrain.CONNECT_STILL_OPEN);

        int first = f.llm.requests().size();
        f.llm.replyText("उनका रेट अच्छा है। क्या मैं उन्हें अभी कॉल करूँ?");
        List<Line> again = List.of(new Line(Speaker.FARMER, "woh kahan ke hain?"),
                new Line(Speaker.ASSISTANT, "वो नासिक के व्यापारी हैं। क्या मैं उन्हें अभी कॉल करूँ?"));
        f.brain.respond(ctx, again, said(ctx, "unka rate kaisa hai?"));
        assertThat(systemPrompt(first)).as("asked once more at most, never on a loop")
                .doesNotContain(LlmAgentBrain.CONNECT_STILL_OPEN);
        assertThat(classifierCalls()).as("a question needs no yes/no reading").isZero();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"haan agar 4500 de to", "haan but only if he pays today", "theek hai lekin 4500 mein",
            "हाँ, अगर आज पैसा दें तो", "phone laga do agar rate achha ho", "yes tomorrow", "haan kal subah",
            "haan ji, wo kab tak aayenge?", "yes bring a truck"})
    void aConditionalYesNeverConnects(String turn) {
        f.llm.replyToolCall("connect_buyer", "{\"buyerId\":\"mehta-agro\"}");
        f.llm.replyText("Theek hai.");
        f.brain.respond(ctx, asked(), said(ctx, turn));
        assertThat(f.connected).as("refused in code, whatever the model wants").isEmpty();
        assertThat(systemPrompt(0)).doesNotContain(LlmAgentBrain.CONNECT_YES_AND_MORE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"yes tomorrow", "yes on delivery", "haan kal", "ಹೌದು ನಾಳೆ"})
    void aQualifiedYesIsNeverRecordedAsConsentToConnect(String turn) {
        f.llm.replyText("ठीक है।");
        f.brain.respond(ctx, asked(), said(ctx, turn));
        assertThat(f.consent.allowed(ctx.callId(), "mehta-agro")).as(turn).isFalse();
        f.consent.heard(ctx.callId(), turn);
        assertThat(f.consent.allowed(ctx.callId(), "mehta-agro")).as(turn + " (consent's own reading)").isFalse();
    }

    @Test
    void anIdentityQuestionAfterTheYesStillConnects() {
        f.llm.replyToolCall("connect_buyer", "{\"buyerId\":\"mehta-agro\"}");
        f.llm.replyText("Main G-One hoon. Mehta Agro ko call kar rahi hoon.");
        f.brain.respond(ctx, asked(), said(ctx, "haan, but tum kaun ho?"));
        assertThat(f.connected).hasSize(1);
    }

    @Test
    void anOrdinaryTurnWithNoQuestionPendingIsUnchanged() {
        f.llm.replyText("आज प्याज़ का भाव देख रही हूँ।");
        f.brain.respond(ctx, List.of(), said(ctx, "haan, pyaz ka bhav kya hai?"));
        assertThat(f.llm.requests()).hasSize(1);
        assertThat(classifierCalls()).isZero();
        assertThat(systemPrompt(0)).doesNotContain(LlmAgentBrain.CONNECT_YES_AND_MORE)
                .doesNotContain(LlmAgentBrain.CONNECT_STILL_OPEN);
    }

    // ---- the returning farmer's name check ----

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"haan lekin pyaz ka bhav kya hai", "हाँ, पर प्याज़ का भाव क्या है?",
            "yes, but what is the onion price?", "haan ji, mandi ka bhav batao"})
    void aYesToTheNameCheckWithAQuestionConfirmsAndTheModelAnswersWithMemory(String turn) {
        CallContext returning = BrainFixture.returning(HINDI, f.seedSuresh());
        f.brain.greet(returning);
        f.llm.replyText(turn.startsWith("yes") ? "Suresh ji, checking today's onion price." : "सुरेश जी, आज का प्याज़ भाव देख रही हूँ।");

        f.brain.respond(returning, List.of(new Line(Speaker.ASSISTANT, "क्या मैं Suresh जी से बात कर रही हूँ?")),
                said(returning, turn));

        assertThat(f.toolEvents).first().satisfies(e -> {
            assertThat(e.tool()).isEqualTo("confirm_name");
            assertThat(e.args().path("confirmed").asBoolean()).isTrue();
        });
        assertThat(f.brain.memoryState(returning.callId())).contains(MemoryGate.State.CONFIRMED);
        assertThat(f.llm.requests()).as("the question goes to the model, once").hasSize(1);
        assertThat(classifierCalls()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"haan kal", "yes tomorrow", "haan, gaadi bhejna", "ಹೌದು ನಾಳೆ"})
    void aQualifiedYesToTheNameCheckNeverUnlocksMemory(String turn) {
        CallContext returning = BrainFixture.returning(HINDI, f.seedSuresh());
        f.brain.greet(returning);
        f.llm.replyToolCall("confirm_name", "{\"confirmed\":true}"); // a model that would confirm is never asked
        f.llm.replyText("ठीक है।");
        f.brain.respond(returning, List.of(new Line(Speaker.ASSISTANT, "क्या मैं Suresh जी से बात कर रही हूँ?")),
                said(returning, turn));
        assertThat(f.brain.memoryState(returning.callId())).as(turn)
                .isNotEqualTo(java.util.Optional.of(MemoryGate.State.CONFIRMED));
    }

    @Test
    void aConditionalYesToTheNameCheckIsNotAYes() {
        CallContext returning = BrainFixture.returning(HINDI, f.seedSuresh());
        f.brain.greet(returning);
        f.brain.respond(returning, List.of(new Line(Speaker.ASSISTANT, "क्या मैं Suresh जी से बात कर रही हूँ?")),
                said(returning, "haan... nahi nahi, pyaz ka bhav kya hai"));
        assertThat(f.brain.memoryState(returning.callId())).isNotEqualTo(java.util.Optional.of(MemoryGate.State.CONFIRMED));
    }
}
