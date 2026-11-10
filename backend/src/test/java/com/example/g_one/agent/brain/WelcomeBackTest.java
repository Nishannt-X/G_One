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
 * The welcome back after a yes to the name check: fixed wording from memory (last crop or newest open item), in Hindi
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
    void anOpenBuyerItemIsNamedWithItsCropInHindiAndEnglish() {
        FarmerMemory m = memory(List.of(NAME), List.of(new CallGist(NEWER, BrainFixture.PAST_MS, BrainFixture.PAST_MS + 1,
                "Suresh asked the red onion price.", List.of("Find a buyer for ~20 quintal red onion after harvest"))));

        assertThat(WELCOME.line("hi-IN", m).orElseThrow().text())
                .isEqualTo("नमस्ते Suresh जी, फिर से स्वागत है। पिछली बार आपने लाल प्याज़ के ख़रीदार की बात की थी। "
                        + "क्या अब ख़रीदार ढूँढूँ, या कुछ और मदद करूँ?");
        assertThat(WELCOME.line("en-IN", m).orElseThrow().text())
                .startsWith("Welcome back, Suresh ji. Last time we talked about a buyer for your red onion.");
        // Hinglish uses the Hindi lines.
        assertThat(WELCOME.line("hi-en", m).orElseThrow().code()).isEqualTo("hi-IN");
    }

    @Test
    void aPickupItemAsksWhetherPickupAndPaymentWentWell() {
        FarmerMemory m = memory(List.of(NAME, fact("crop:tomato", "tomato", BrainFixture.PAST_MS)),
                List.of(new CallGist(NEWER, BrainFixture.PAST_MS, BrainFixture.PAST_MS + 1, "Deal for tomato agreed.",
                        List.of("Check pickup and payment with Shinde Traders (due ~Monday)"))));

        assertThat(WELCOME.line("hi-IN", m).orElseThrow().text()).contains("पिछली बार टमाटर का सौदा तय हुआ था")
                .contains("पिकअप और भुगतान");
        assertThat(WELCOME.line("en-IN", m).orElseThrow().text()).contains("Last time you agreed a tomato deal.")
                .contains("pickup and payment");
    }

    @Test
    void withNoOpenItemTheLastCallsCropIsNamedElseTheNewestCropFact() {
        FarmerMemory fromGist = memory(List.of(NAME, fact("crop:tomato", "tomato", BrainFixture.PAST_MS)),
                List.of(new CallGist(NEWER, BrainFixture.PAST_MS, BrainFixture.PAST_MS + 1,
                        "Suresh asked the wheat price at Lasalgaon.", List.of())));
        assertThat(WELCOME.line("hi-IN", fromGist).orElseThrow().text()).contains("पिछली बार आपने गेहूँ की बात की थी");
        assertThat(WELCOME.line("en-IN", fromGist).orElseThrow().text()).contains("Last time we talked about your wheat.");

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
            assertThat(r.text()).contains("Suresh जी, फिर से स्वागत है").contains("लाल प्याज़ के ख़रीदार");
            assertThat(off.brain.memoryState(hi.callId())).contains(MemoryGate.State.CONFIRMED);

            CallContext en = BrainFixture.returning(ENGLISH, off.seedSuresh());
            off.brain.greet(en);
            Reply e = off.brain.respond(en, List.of(), said(en, "yes"));
            assertThat(e.text()).startsWith("Welcome back, Suresh ji.").contains("a buyer for your red onion");
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

            assertThat(r.text()).contains("लाल प्याज़ के ख़रीदार");
            assertThat(f.llm.requests()).hasSize(1);
            assertThat(f.brain.memoryState(ctx.callId())).contains(MemoryGate.State.CONFIRMED);
        }
    }
}
