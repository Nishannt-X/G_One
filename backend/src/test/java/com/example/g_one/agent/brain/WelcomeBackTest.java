package com.example.g_one.agent.brain;

import static com.example.g_one.agent.brain.BrainFixture.ENGLISH;
import static com.example.g_one.agent.brain.BrainFixture.HINDI;
import static com.example.g_one.agent.brain.BrainFixture.said;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.AgentBrain.Line;
import com.example.g_one.agent.AgentBrain.Reply;
import com.example.g_one.agent.AgentBrain.Speaker;
import com.example.g_one.agent.tools.memory.MemoryBlock;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.ProfileFact;
import com.example.g_one.core.Provenance;
import com.example.g_one.fakes.FakeLeg;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The welcome back after a yes to the name check: fixed wording from memory (the newest open item read back, else a remembered crop), in Hindi
 * and English, with no model. All names and data are synthetic.
 */
class WelcomeBackTest {
    private static final WelcomeBack WELCOME = new WelcomeBack(BrainFixture.JSON);
    private static final CallId OLD = CallId.of("c-1028");
    private static final CallId NEWER = CallId.of("c-1103");
    private static final long DAY = 86_400_000L;

    private static FarmerMemory memory(List<ProfileFact> facts, List<CallGist> gists) {
        return FarmerMemory.of(FakeLeg.FARMER_KEY, facts, gists);
    }

    private static ProfileFact fact(String field, String value, long atMs) {
        return new ProfileFact(field, value, Provenance.said(OLD, atMs));
    }

    private static final ProfileFact NAME = fact("name", "Suresh", BrainFixture.PAST_MS);

    @Test
    void anOpenItemIsReadBackAsStoredInHindiAndEnglish() {
        FarmerMemory m = memory(List.of(NAME), List.of(new CallGist(NEWER, BrainFixture.PAST_MS, BrainFixture.PAST_MS + 1,
                "Suresh asked the red onion price.", List.of("Find a buyer for ~20 quintal red onion after harvest"))));

        assertThat(WELCOME.line("hi-IN", m).orElseThrow().text())
                .isEqualTo("नमस्ते Suresh जी, फिर से स्वागत है। पहले मैंने आगे देखने के लिए यह बात नोट की थी: "
                        + "find a buyer for about twenty quintal red onion after harvest। क्या यह हो गया, या कुछ और मदद करूँ?");
        assertThat(WELCOME.line("en-IN", m).orElseThrow().text())
                .isEqualTo("Welcome back, Suresh ji. Earlier I noted this to follow up: find a buyer for about twenty quintal "
                        + "red onion after harvest. Did that get done, or can I help with something else?");
        // Hinglish uses the Hindi lines.
        assertThat(WELCOME.line("hi-en", m).orElseThrow().code()).isEqualTo("hi-IN");
    }

    @Test
    void aPaymentOrPickupItemNeverClaimsADealWasAgreed() {
        // "payment" here is only a question the farmer had; nothing in memory records a confirmed deal.
        FarmerMemory m = memory(List.of(NAME, fact("crop:tomato", "tomato", BrainFixture.PAST_MS)),
                List.of(new CallGist(NEWER, BrainFixture.PAST_MS, BrainFixture.PAST_MS + 1, "Suresh asked about buyers.",
                        List.of("Ask Shinde Traders about their payment terms (due ~Monday)."))));

        String en = WELCOME.line("en-IN", m).orElseThrow().text();
        assertThat(en).contains("Earlier I noted this to follow up: ask Shinde Traders about their payment terms (due about Monday).")
                .doesNotContainIgnoringCase("agreed").doesNotContainIgnoringCase("deal").doesNotContainIgnoringCase("last time");
        String hi = WELCOME.line("hi-IN", m).orElseThrow().text();
        assertThat(hi).contains("ask Shinde Traders about their payment terms").doesNotContain("सौदा").doesNotContain("पिछली बार");
    }

    @Test
    void anOpenItemFromAnOlderCallIsNotCalledLastTime() {
        FarmerMemory m = memory(List.of(NAME), List.of(
                new CallGist(NEWER, BrainFixture.PAST_MS, BrainFixture.PAST_MS + 1,
                        "Asked about red onion prices.", List.of()),
                new CallGist(OLD, BrainFixture.PAST_MS - DAY, BrainFixture.PAST_MS - DAY + 1,
                        "Talked about tomato.", List.of("Check pickup and payment with Shinde Traders"))));
        assertThat(WELCOME.line("en-IN", m).orElseThrow().text())
                .contains("Earlier I noted this to follow up: check pickup and payment with Shinde Traders.")
                .doesNotContainIgnoringCase("last time").doesNotContain("red onion");
    }

    @Test
    void aMessyItemIsReadBackOnOneLineAndNeverShortened() {
        assertThat(WelcomeBack.spokenItem("Find a buyer\nfor onion")).isEqualTo("find a buyer for onion");
        assertThat(WelcomeBack.spokenItem("APMC visit on Monday.")).isEqualTo("APMC visit on Monday");
        String long_ = "Check with Shinde Traders about the twenty quintal onion pickup on Monday morning at the farm "
                + "gate and the payment on delivery, but do not call the buyer";
        assertThat(WelcomeBack.spokenItem(long_)).endsWith("do not call the buyer");
    }

    @Test
    void anItemTooLongToReadWholeIsMentionedWithoutItsWordsSoNoNegationIsLost() {
        String long_ = "Check with Shinde Traders about the twenty quintal onion pickup on Monday morning at the farm "
                + "gate and the payment on delivery, but do not call the buyer";
        assertThat(long_.length()).isGreaterThan(WelcomeBack.MAX_ITEM_CHARS);
        FarmerMemory m = memory(List.of(NAME), List.of(new CallGist(NEWER, BrainFixture.PAST_MS, BrainFixture.PAST_MS + 1,
                "Talked about onion.", List.of(long_))));

        assertThat(WELCOME.line("en-IN", m).orElseThrow().text()).isEqualTo("Welcome back, Suresh ji. Earlier I noted "
                + "something to follow up. Shall we look at that, or can I help with something else?");
        assertThat(WELCOME.line("hi-IN", m).orElseThrow().text()).contains("एक बात नोट की थी").doesNotContain("Shinde")
                .doesNotContain("buyer");
    }

    @Test
    void withNoOpenItemTheLastCallsCropIsNamedElseTheNewestCropFact() {
        FarmerMemory fromGist = memory(List.of(NAME, fact("crop:tomato", "tomato", BrainFixture.PAST_MS)),
                List.of(new CallGist(NEWER, BrainFixture.PAST_MS, BrainFixture.PAST_MS + 1,
                        "Suresh asked the wheat price at Lasalgaon.", List.of())));
        assertThat(WELCOME.line("hi-IN", fromGist).orElseThrow().text()).contains("पहले आपने गेहूँ की बात की थी");
        assertThat(WELCOME.line("en-IN", fromGist).orElseThrow().text()).contains("Earlier we talked about your wheat.");

        FarmerMemory fromFacts = memory(List.of(NAME, fact("crop:tomato", "tomato", BrainFixture.PAST_MS - DAY),
                fact("crop:red onion", "red onion", BrainFixture.PAST_MS)), List.of());
        assertThat(WELCOME.line("en-IN", fromFacts).orElseThrow().text()).contains("your red onion");

        FarmerMemory nothing = memory(List.of(NAME), List.of());
        assertThat(WELCOME.line("en-IN", nothing).orElseThrow().text())
                .isEqualTo("Welcome back, Suresh ji. How can I help today?");
    }

    @Test
    void anotherSpeakersOldGistIsNeverRecalled() {
        FarmerMemory m = memory(List.of(NAME), List.of(
                new CallGist(NEWER, BrainFixture.PAST_MS, BrainFixture.PAST_MS + 1,
                        MemoryBlock.OTHER_SPEAKER_PREFIX + "Someone asked for a cotton buyer.", List.of("Find a cotton buyer")),
                new CallGist(OLD, BrainFixture.PAST_MS - DAY, BrainFixture.PAST_MS - DAY + 1,
                        "Suresh asked the potato price.", List.of())));

        assertThat(WELCOME.line("en-IN", m).orElseThrow().text()).contains("your potato").doesNotContain("cotton");
    }

    @Test
    void noLinesForALanguageOrNoUsableNameMeansNoWelcomeBack() {
        assertThat(WELCOME.line("bn-IN", memory(List.of(NAME), List.of()))).isEmpty();
        assertThat(WELCOME.line("hi-IN", memory(List.of(fact("district", "Nashik", 1)), List.of()))).isEmpty();
    }

    // ---- through the brain --------------------------------------------------------------------------------------

    @Test
    void withoutAModelAYesToTheNameCheckHearsTheWelcomeBackInHindiAndEnglish() throws Exception {
        try (BrainFixture off = new BrainFixture(false)) {
            CallContext hi = BrainFixture.returning(HINDI, off.seedSuresh());
            off.brain.greet(hi);
            Reply r = off.brain.respond(hi, List.of(new Line(Speaker.ASSISTANT, "क्या मैं Suresh जी से बात कर रही हूँ?")),
                    said(hi, "हाँ"));
            assertThat(r.text()).contains("Suresh जी, फिर से स्वागत है").contains("find a buyer for about twenty quintal red onion");
            assertThat(off.brain.memoryState(hi.callId())).contains(MemoryGate.State.CONFIRMED);

            CallContext en = BrainFixture.returning(ENGLISH, off.seedSuresh());
            off.brain.greet(en);
            Reply e = off.brain.respond(en, List.of(), said(en, "yes"));
            assertThat(e.text()).startsWith("Welcome back, Suresh ji.").contains("follow up: find a buyer for about twenty quintal red onion");
            assertThat(e.language()).isEqualTo(ENGLISH);
        }
    }

    @Test
    void aBareYesToTheModelGetsTheFixedWelcomeBackWithoutASecondModelCall() throws Exception {
        try (BrainFixture f = new BrainFixture()) {
            CallContext ctx = BrainFixture.returning(HINDI, f.seedSuresh());
            f.brain.greet(ctx);
            f.llm.replyToolCall("confirm_name", "{\"confirmed\":true}");

            Reply r = f.brain.respond(ctx, List.of(), said(ctx, "हाँ जी"));

            assertThat(r.text()).contains("find a buyer for about twenty quintal red onion");
            assertThat(f.llm.requests()).hasSize(1);
            assertThat(f.brain.memoryState(ctx.callId())).contains(MemoryGate.State.CONFIRMED);
        }
    }
}
