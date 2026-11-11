package com.example.g_one.agent.brain;

import static com.example.g_one.agent.brain.BrainFixture.HINDI;
import static com.example.g_one.agent.brain.BrainFixture.said;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.AgentBrain.Line;
import com.example.g_one.agent.AgentBrain.Reply;
import com.example.g_one.agent.AgentBrain.Speaker;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.example.g_one.fakes.FakeLeg;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The owner's Hindi test call, turn by turn, against the real brain and tools with a scripted fake model. The caller ID
 * matched a synthetic returning farmer "आमीना चिराग" (crop onion), but the caller was someone else (synthetic "अनिकेत")
 * who wanted to sell maize. In the live call the brain asked the remembered name again after a clear no, lost the name,
 * answered failures with an unrelated "price or weather?" question, offered a phone number and pointed to an online
 * directory. Each of those is pinned here.
 */
class OwnerCallRegressionTest {
    private static final String REMEMBERED = "आमीना चिराग";
    private static final String TOOL_REFUSED = "{\"error\":{\"message\":\"tool call validation failed\","
            + "\"type\":\"invalid_request_error\",\"code\":\"tool_use_failed\"}}";

    private BrainFixture f;
    private CallContext ctx;
    private final List<Line> lines = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        f = new BrainFixture(true, true);
        f.store.upsertFacts(FakeLeg.FARMER_KEY, List.of(
                new ProfileFact("name", REMEMBERED, Provenance.said(BrainFixture.PAST_CALL, BrainFixture.PAST_MS)),
                new ProfileFact("crops", "onion", Provenance.said(BrainFixture.PAST_CALL, BrainFixture.PAST_MS))));
        f.store.calls.set(0);
        FarmerMemory memory = f.store.load(FakeLeg.FARMER_KEY).orElseThrow();
        ctx = BrainFixture.returning(HINDI, memory);
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    private Reply turn(String farmer) {
        Reply r = f.brain.respond(ctx, List.copyOf(lines), said(ctx, farmer));
        lines.add(new Line(Speaker.FARMER, farmer));
        lines.add(new Line(Speaker.ASSISTANT, r.text()));
        return r;
    }

    private int requests() {
        return f.llm.requests().size();
    }

    @Test
    void theOwnersCallTurnByTurn() {
        Reply greeting = f.brain.greet(ctx);
        assertThat(greeting.text()).contains(REMEMBERED);
        lines.add(new Line(Speaker.ASSISTANT, greeting.text()));

        // A request before the answer: held, and the check is asked once more, without a model.
        Reply first = turn("मुझे भुट्टा बेचना है");
        assertThat(first.text()).contains(REMEMBERED).contains("मदद");
        assertThat(requests()).isZero();

        // A clear no: someone else for the rest of the call. Their name is asked once.
        Reply no = turn("नहीं");
        assertThat(no.text()).isEqualTo("ठीक है, माफ़ कीजिए। आपका नाम क्या है?");
        assertThat(f.brain.memoryState(ctx.callId())).contains(MemoryGate.State.OTHER_SPEAKER);
        assertThat(f.brain.awaitingIdentity(ctx.callId())).isFalse();
        assertThat(f.memoryEvents).as("the console stops showing the remembered profile as the caller's")
                .singleElement().satisfies(m -> {
                    assertThat(m.facts()).isEmpty();
                    assertThat(m.gists()).isEmpty();
                    assertThat(m.returning()).isFalse();
                });
        assertThat(requests()).isZero();

        // The bare name, and the model fails as on the live call (HTTP 400 tool_use_failed, twice): the caller is
        // thanked by name and the held sale goes on to its next step, not "price or weather?".
        f.llm.replyError(400, TOOL_REFUSED).replyError(400, TOOL_REFUSED);
        Reply name = turn("अनिकेत");
        assertThat(name.text()).startsWith("धन्यवाद अनिकेत जी।").contains("मक्का").contains("कितने क्विंटल")
                .doesNotContain("भाव जानना चाहते हैं या मौसम");
        assertThat(requests()).isEqualTo(2);
        assertThat(f.llm.requests().get(1).has("tools")).as("the retry after a refused tool call has no tools").isFalse();

        f.llm.replyText("बताइए, आप क्या जानना चाहते हैं?");
        turn("नहीं मैं कुछ और जानना चाहता हूँ");

        // The model repeats the name check it saw on the live call: that sentence never reaches the caller.
        f.llm.replyText("क्या आप आमीना चिराग हैं?");
        int before = requests();
        Reply sale = turn("मेरा नाम अनिकेत है और मैं भुटा भेजना चाहता हूँ।");
        assertThat(sale.text()).doesNotContain(REMEMBERED).doesNotContain("आमीना").contains("कितने क्विंटल");
        JsonNode request = f.llm.requests().get(before);
        assertThat(request.toString()).as("nothing remembered, and no replayed name check, reaches the model")
                .doesNotContain("आमीना").doesNotContain("onion").contains("अनिकेत");

        // Hidden reasoning spent the budget twice: the pending question is asked again, not the name check.
        f.llm.replyOnlyReasoning().replyOnlyReasoning();
        Reply refused = turn("नहीं मैंने मना करा पहले आपको");
        assertThat(refused.text()).contains("कितने क्विंटल").doesNotContain("आमीना").doesNotContain("मौसम");

        // Advice to look elsewhere is dropped; the sale goes on.
        f.llm.replyText("भुट्टे को बेचने के लिए आप स्थानीय मंडी या ऑनलाइन खरीदार निर्देशिका देख सकते हैं।");
        Reply maize = turn("भुट्टा");
        assertThat(maize.text()).doesNotContain("निर्देशिका").doesNotContain("ऑनलाइन").contains("कितने क्विंटल");

        // "Connect me to someone": the buyer search runs, and no phone number is offered.
        f.llm.replyToolCall("find_buyers", "{\"commodity\":\"maize\"}");
        f.llm.replyText("भुट्टे के लिये निकटतम खरीदार का फ़ोन नंबर मेरे पास है। क्या मैं आपको अभी कॉल करूँ?");
        Reply connect = turn("आप मुझे किसी से कनेक्ट कीजिए");
        assertThat(f.consent.suggested(ctx.callId())).isNotEmpty();
        assertThat(connect.text()).doesNotContain("नंबर मेरे पास").startsWith("मैं किसी का फ़ोन नंबर नहीं बताती")
                .endsWith("?");

        // No dated price for maize: said once, then the useful next step instead of the same line again.
        f.llm.replyToolCall("get_mandi_price", "{\"commodity\":\"maize\"}");
        Reply price = turn("क्या भाव में बेच रहे हैं");
        assertThat(price.text()).contains("तारीख वाला भाव नहीं");
        f.llm.replyToolCall("get_mandi_price", "{\"commodity\":\"maize\"}");
        Reply again = turn("भाव बताइए");
        assertThat(again.text()).doesNotContain("तारीख वाला भाव नहीं").contains("खरीदार");

        assertThat(lines.subList(4, lines.size())).extracting(Line::text).as("never the remembered name after the no")
                .noneMatch(t -> t.contains("आमीना"));
        assertThat(f.store.calls).as("nothing written for someone else").hasValue(0);
        assertThat(f.brain.memoryState(ctx.callId())).contains(MemoryGate.State.OTHER_SPEAKER);
    }

    @Test
    void aNameGivenWithTheNoIsTakenAndTheRequestInTheSameTurnGoesOn() {
        lines.add(new Line(Speaker.ASSISTANT, f.brain.greet(ctx).text()));
        f.llm.replyText("ज़रूर, कितने क्विंटल मक्का है?");
        Reply r = turn("नहीं, मैं राम हूँ, मुझे भुट्टा बेचना है");
        assertThat(r.text()).startsWith("धन्यवाद राम जी।").contains("कितने क्विंटल").doesNotContain("आमीना");
        assertThat(f.brain.memoryState(ctx.callId())).contains(MemoryGate.State.OTHER_SPEAKER);
        assertThat(f.llm.requests()).hasSize(1);
        assertThat(f.llm.requests().get(0).toString()).doesNotContain("आमीना").contains("भुट्टा बेचना है");
    }

    @Test
    void aDifferentNameWhileTheCheckWaitsCountsAsANo() {
        lines.add(new Line(Speaker.ASSISTANT, f.brain.greet(ctx).text()));
        Reply r = turn("मेरा नाम अनिकेत है");
        assertThat(f.brain.memoryState(ctx.callId())).contains(MemoryGate.State.OTHER_SPEAKER);
        assertThat(r.text()).startsWith("धन्यवाद अनिकेत जी।").doesNotContain("आमीना");
        assertThat(f.llm.requests()).isEmpty();
    }

    @Test
    void denialsWithoutANameAreANo() {
        for (String denial : List.of("मैं वो नहीं हूँ", "main woh nahi hoon", "I am someone else", "wrong number",
                "ग़लत नंबर है")) {
            BrainFixture.CountingStore store = f.store;
            CallContext call = BrainFixture.returning(HINDI, store.load(FakeLeg.FARMER_KEY).orElseThrow());
            f.brain.greet(call);
            f.brain.respond(call, List.of(), said(call, denial));
            assertThat(f.brain.memoryState(call.callId())).as(denial).contains(MemoryGate.State.OTHER_SPEAKER);
        }
        assertThat(f.llm.requests()).isEmpty();
    }

    @Test
    void anUnclearRequestTwiceFailsClosedToSomeoneElse() {
        lines.add(new Line(Speaker.ASSISTANT, f.brain.greet(ctx).text()));
        turn("मुझे भुट्टा बेचना है");
        assertThat(f.brain.memoryState(ctx.callId())).contains(MemoryGate.State.NAME_CHECK);
        Reply r = turn("भाव क्या चल रहा है भुट्टे का");
        assertThat(f.brain.memoryState(ctx.callId())).contains(MemoryGate.State.OTHER_SPEAKER);
        assertThat(r.text()).contains("आपका नाम क्या है");
    }

    @Test
    void aYesAfterAHeldRequestWelcomesBackAndTakesItUp() {
        lines.add(new Line(Speaker.ASSISTANT, f.brain.greet(ctx).text()));
        turn("मुझे भुट्टा बेचना है");
        f.llm.replyText("ज़रूर, कितने क्विंटल मक्का है?");
        Reply r = turn("हाँ जी");
        assertThat(f.brain.memoryState(ctx.callId())).contains(MemoryGate.State.CONFIRMED);
        assertThat(r.text()).startsWith("नमस्ते " + REMEMBERED + " जी।").contains("कितने क्विंटल");
        assertThat(f.llm.requests()).hasSize(1);
        assertThat(f.llm.requests().get(0).toString()).contains("भुट्टा बेचना है");
    }
}
