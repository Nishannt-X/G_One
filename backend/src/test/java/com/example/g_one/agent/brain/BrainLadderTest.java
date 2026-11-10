package com.example.g_one.agent.brain;

import static com.example.g_one.agent.brain.BrainFixture.ENGLISH;
import static com.example.g_one.agent.brain.BrainFixture.HINDI;
import static com.example.g_one.agent.brain.BrainFixture.said;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.AgentBrain.Line;
import com.example.g_one.agent.AgentBrain.Reply;
import com.example.g_one.agent.AgentBrain.Speaker;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.Language;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Questions answered without a model, and the ladder when no model can answer. Fake model only. */
class BrainLadderTest {
    private static final String OLD_APOLOGY = "माफ़ कीजिए, अभी थोड़ी दिक्कत";
    private BrainFixture f;
    private final List<ConsoleEvent.TurnMeasured> measured = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        f = new BrainFixture();
        f.bus.subscribe(e -> {
            if (e instanceof ConsoleEvent.TurnMeasured t) measured.add(t);
        });
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    @Test
    void howDoYouKnowMyNameIsAnsweredTruthfullyWithoutAModelInEveryMemoryState() {
        CallContext fresh = BrainFixture.newFarmer(HINDI);
        assertThat(f.brain.respond(fresh, List.of(), said(fresh, "आपको मेरे नाम कैसे पता?")).text())
                .contains("नाम नहीं पता").contains("आपका नाम क्या है");

        CallContext returning = BrainFixture.returning(HINDI, f.seedSuresh());
        f.brain.greet(returning);
        Reply check = f.brain.respond(returning, List.of(), said(returning, "aapko mere naam kaise pata?"));
        assertThat(check.text()).contains("Suresh").contains("इसी नंबर से").endsWith("?").doesNotContain("Nashik");
        assertThat(f.gate.peek(returning.callId())).contains(MemoryGate.State.NAME_CHECK);

        CallContext withheld = BrainFixture.withheld(ENGLISH);
        assertThat(f.brain.respond(withheld, List.of(), said(withheld, "how do you know my name?")).text())
                .contains("cannot see your number");
        assertThat(f.llm.requests()).isEmpty();
    }

    @Test
    void aNameGivenOnThisCallIsCitedAsGivenOnThisCall() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        f.llm.replyToolCall("save_profile_fact", "{\"field\":\"name\",\"value\":\"रमेश\"}").replyText("धन्यवाद रमेश जी।");
        f.brain.respond(ctx, List.of(), said(ctx, "मेरा नाम रमेश है"));
        assertThat(f.brain.respond(ctx, List.of(), said(ctx, "मेरा नाम क्या है?")).text()).contains("रमेश").contains("इसी बातचीत");
    }

    @Test
    void whoAreYouAndRepeatNeedNoModel() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        assertThat(f.brain.respond(ctx, List.of(), said(ctx, "आप कौन बोल रही हैं?")).text()).contains("AI");
        assertThat(f.brain.respond(ctx, List.of(new Line(Speaker.ASSISTANT, "प्याज़ का भाव बताया।")), said(ctx, "फिर से बोलिए")).text())
                .isEqualTo("प्याज़ का भाव बताया।");
        assertThat(f.llm.requests()).isEmpty();
        assertThat(measured).allSatisfy(m -> assertThat(m.handler()).isEqualTo("local"));
    }

    @Test
    void theReplyFollowsTheCallersLastTurnLanguage() {
        CallContext hindiCall = BrainFixture.newFarmer(HINDI);
        Reply e = f.brain.respond(hindiCall, List.of(), said(hindiCall, "who are you?"));
        assertThat(e.language()).isEqualTo(ENGLISH);
        assertThat(e.text()).startsWith("I am G-One");

        CallContext englishCall = BrainFixture.newFarmer(ENGLISH);
        Reply h = f.brain.respond(englishCall, List.of(), said(englishCall, "aap kaun ho?"));
        assertThat(h.language()).isEqualTo(HINDI);
        assertThat(h.text()).contains("AI");
    }

    @Test
    void noModelGivesThePriceFromTheToolInTheFarmersLanguage() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        f.llm.failures.set(1);
        Reply r = f.brain.respond(ctx, List.of(), said(ctx, "प्याज का भाव क्या है?"));
        assertThat(r.text()).contains("प्याज़").contains("रुपये").contains("तारीख").doesNotContainPattern("\\d");
        assertThat(r.text()).doesNotContain(OLD_APOLOGY);
    }

    @Test
    void noModelOnAByeHangsUpAndOnAYesGivesANextStep() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        f.llm.failures.set(2);
        assertThat(f.brain.respond(ctx, List.of(), said(ctx, "ठीक है अलविदा")).endCall()).isTrue();
        assertThat(f.brain.respond(ctx, List.of(), said(ctx, "हाँ")).text()).contains("फिर बोलिए");
    }

    @Test
    void noModelAndNoIntentSaysOneVariedHonestLineThenThatItIsBusy() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        f.llm.failures.set(50);
        List<String> heard = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            heard.add(f.brain.respond(ctx, List.of(), said(ctx, "खेत की मिट्टी के बारे में बताओ " + i)).text());
        }
        assertThat(heard).allSatisfy(l -> assertThat(l).doesNotContain(OLD_APOLOGY).doesNotContain("Sorry"));
        for (int i = 1; i < heard.size(); i++) assertThat(heard.get(i)).isNotEqualTo(heard.get(i - 1));
        assertThat(heard.get(0)).endsWith("?");
        assertThat(heard.get(1)).contains("व्यस्त");
        assertThat(heard.get(2)).contains("व्यस्त");
        assertThat(measured).allSatisfy(m -> {
            assertThat(m.handler()).isEqualTo("fallback");
            assertThat(m.failure()).isEqualTo("upstream");
        });
    }

    @Test
    void aSuccessEndsTheBusyStreak() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        f.llm.failures.set(2);
        f.brain.respond(ctx, List.of(), said(ctx, "मिट्टी कैसी है"));
        f.brain.respond(ctx, List.of(), said(ctx, "मिट्टी कैसी है"));
        f.llm.replyText("मिट्टी की जाँच कराइए।");
        assertThat(f.brain.respond(ctx, List.of(), said(ctx, "मिट्टी कैसी है")).text()).contains("जाँच");
        f.llm.failures.set(1);
        assertThat(f.brain.respond(ctx, List.of(), said(ctx, "मिट्टी कैसी है")).text()).endsWith("?");
    }

    @Test
    void anOfflineYesAnswersTheNameCheckSoACallerIsNotStuck() {
        CallContext ctx = BrainFixture.returning(HINDI, f.seedSuresh());
        f.brain.greet(ctx);
        f.llm.failures.set(1);
        assertThat(f.brain.respond(ctx, List.of(), said(ctx, "हाँ")).text()).contains("Suresh जी");
        assertThat(f.gate.peek(ctx.callId())).contains(MemoryGate.State.CONFIRMED);
    }

    @Test
    void modelRepliesAreCappedAtThreeSentencesAndDigitsBecomeWords() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        f.llm.replyText("पहला वाक्य। दूसरा वाक्य। तीसरा 45 वाक्य। चौथा वाक्य।");
        Reply r = f.brain.respond(ctx, List.of(), said(ctx, "खेती के बारे में बताओ"));
        assertThat(r.text()).isEqualTo("पहला वाक्य। दूसरा वाक्य। तीसरा पैंतालीस वाक्य।");
    }

    @Test
    void aReplyInTheWrongLanguageGetsOneCorrectionAndAStatedNameIsSavedByCode() {
        CallContext ctx = BrainFixture.newFarmer(ENGLISH);
        f.llm.replyText("नमस्ते प्रिया जी, आज आपकी फसल में क्या मदद चाहिए?").replyText("Hello Priya, how can I help with your crop today?");
        Reply r = f.brain.respond(ctx, List.of(), said(ctx, "my name is Priya"));
        assertThat(r.text()).startsWith("Hello Priya");
        assertThat(r.language()).isEqualTo(ENGLISH);
        assertThat(f.llm.requests()).hasSize(2);
        assertThat(f.toolEvents).singleElement().satisfies(e -> {
            assertThat(e.tool()).isEqualTo("save_profile_fact");
            assertThat(e.args().path("value").asText()).isEqualTo("Priya");
        });
    }

    @Test
    void romanisedHindiToAnEnglishTurnCountsAsTheWrongLanguage() {
        assertThat(ReplyLanguage.wrongFor("Kisan Credit Card ek bank ka card hai aur kisan ko paisa kaise milta hai", ENGLISH)).isTrue();
        assertThat(ReplyLanguage.wrongFor("The Kisan Credit Card is a bank card for a kisan.", ENGLISH)).isFalse();
        assertThat(ReplyLanguage.wrongFor("Fasal bima ke liye apne office se milna hai.", HINDI)).isTrue();
        assertThat(ReplyLanguage.wrongFor("ठीक", ENGLISH)).as("too short to judge").isFalse();
    }

    @Test
    void aQuestionAboutTheNameIsNotAStatedName() {
        assertThat(TurnIntent.statedName("मेरा नाम क्या है")).isEmpty();
        assertThat(TurnIntent.statedName("मेरा नाम रमेश है")).contains("रमेश");
        assertThat(TurnIntent.statedName("mera naam Ramesh Kumar hai")).contains("Ramesh Kumar");
        assertThat(TurnIntent.statedName("আমার নাম সুমন")).contains("সুমন");
    }

    @Test
    void noModelOnASaleSaysItHeardTheSale() {
        CallContext ctx = BrainFixture.newFarmer(ENGLISH);
        f.llm.failures.set(1);
        assertThat(f.brain.respond(ctx, List.of(), said(ctx, "I want to sell ten quintal onion")).text()).contains("you want to sell");
    }

    @Test
    void aRupeeAmountNothingSupportsIsDroppedButOneTheFarmerSaidStays() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        f.llm.replyText("इस योजना में हर साल पंद्रह हजार रुपये मिलते हैं। बैंक में जाकर पूछिए।");
        assertThat(f.brain.respond(ctx, List.of(), said(ctx, "योजना कैसी है")).text())
                .isEqualTo("बैंक में जाकर पूछिए। रकम की पक्की जानकारी अपने कृषि कार्यालय से लीजिए।");
        f.llm.replyText("आपको ₹ पाँच हजार का भत्ता मिलता है। बैंक में पूछिए।");
        assertThat(f.brain.respond(ctx, List.of(), said(ctx, "योजना का पैसा कैसे मिलता है")).text()).doesNotContain("₹").doesNotContain("पाँच हजार");
        f.llm.replyText("आपने चार हजार रुपये कहा, यह समझ गई।");
        assertThat(f.brain.respond(ctx, List.of(), said(ctx, "मुझे चार हजार रुपये चाहिए")).text()).contains("चार हजार रुपये");
    }

    @Test
    void theNameIsAskedAfterTheFirstAnsweredTurnEvenDuringBusiness() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        f.llm.replyText("जी।").replyText("जी।").replyText("जी।");
        f.brain.respond(ctx, List.of(), said(ctx, "प्याज बेचना है"));
        assertThat(f.llm.requests().get(0).toString()).as("the first business turn is helped first")
                .doesNotContain(LlmAgentBrain.NAME_ASK);
        f.brain.respond(ctx, List.of(), said(ctx, "प्याज बेचना है"));
        assertThat(f.llm.requests().get(1).toString()).contains(LlmAgentBrain.NAME_ASK);

        CallContext chat = BrainFixture.newFarmer(HINDI);
        f.brain.respond(chat, List.of(), said(chat, "खेत की मिट्टी कैसी हो"));
        assertThat(f.llm.requests().get(2).toString()).as("a non-business first turn may ask at once")
                .contains(LlmAgentBrain.NAME_ASK);
    }

    @Test
    void aCropOrSchemeWordInAnEnglishSentenceDoesNotMakeItHinglish() {
        Language.Registry r = f.registry;
        assertThat(ReplyLanguage.ofTurn("what is the Kisan Credit Card?", HINDI, r)).isEqualTo(ENGLISH);
        assertThat(ReplyLanguage.ofTurn("asdf jkl qwer", HINDI, r)).isEqualTo(HINDI);
        assertThat(ReplyLanguage.ofTurn("fasal bima kaise karwayein", ENGLISH, r)).isEqualTo(HINDI);
    }

    @Test
    void onlyTheToolsATurnCanUseAreOffered() {
        CallContext ctx = BrainFixture.newFarmer(HINDI);
        f.llm.replyText("जी।");
        f.brain.respond(ctx, List.of(), said(ctx, "खेत में पानी कब दूँ?"));
        assertThat(f.llm.requests().get(0).path("tools").toString()).doesNotContain("get_mandi_price").contains("end_call");
    }
}
