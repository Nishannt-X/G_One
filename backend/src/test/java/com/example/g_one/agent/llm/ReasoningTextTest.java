package com.example.g_one.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The shared reasoning sanitizer: every shape of leaked thinking a Groq raw answer can take. */
class ReasoningTextTest {
    private static final FallbackLines FALLBACK = new FallbackLines(new ObjectMapper());

    @Test
    void aCompleteBlockIsRemoved() {
        assertThat(ReasoningText.strip("<think>The farmer wants weather.</think>\n Namaste ji.")).isEqualTo("Namaste ji.");
        assertThat(ReasoningText.strip("<think>a</think>Ek<think>\nb\n</think> do")).isEqualTo("Ek do");
    }

    @Test
    void anUnclosedBlockIsRemovedToTheEnd() {
        assertThat(ReasoningText.strip("<think>The farmer asked about wheat, so I should")).isEmpty();
        assertThat(ReasoningText.strip("Gehun ka bhav. <think>now check the")).isEqualTo("Gehun ka bhav.");
    }

    @Test
    void everythingBeforeALoneClosingTagIsRemoved() {
        assertThat(ReasoningText.strip("The user wants a price.\n</think>\n\nBhav 2400 hai.")).isEqualTo("Bhav 2400 hai.");
        assertThat(ReasoningText.strip("plan</think>a</think>Haan ji")).isEqualTo("Haan ji");
    }

    @Test
    void tagVariantsCaseAndAttributesAreCovered() {
        assertThat(ReasoningText.strip("<thinking>plan</thinking>Haan ji")).isEqualTo("Haan ji");
        assertThat(ReasoningText.strip("<reasoning type=\"x\">plan</reasoning>Haan ji")).isEqualTo("Haan ji");
        assertThat(ReasoningText.strip("<THINK>plan</THINK>Haan ji")).isEqualTo("Haan ji");
        assertThat(ReasoningText.strip("< think >plan</ think >Haan ji")).isEqualTo("Haan ji");
        assertThat(ReasoningText.strip("<Thinking>unclosed")).isEmpty();
    }

    @Test
    void plainTextIsUntouchedApartFromTrimming() {
        assertThat(ReasoningText.strip("  I think the price is 2400 <b>today</b>. ")).isEqualTo("I think the price is 2400 <b>today</b>.");
        assertThat(ReasoningText.strip("{\"text\":\"think\"}")).isEqualTo("{\"text\":\"think\"}");
        assertThat(ReasoningText.strip(null)).isEmpty();
    }

    @Test
    void hasTagFindsAnyReasoningTag() {
        assertThat(ReasoningText.hasTag("a <think> b")).isTrue();
        assertThat(ReasoningText.hasTag("a </Reasoning> b")).isTrue();
        assertThat(ReasoningText.hasTag("I think so")).isFalse();
        assertThat(ReasoningText.hasTag(null)).isFalse();
    }

    @Test
    void speakableStripsAndFallsBackToTheApologyWhenNothingIsLeft() {
        assertThat(ReasoningText.speakable("<think>x</think>Namaste", "hi-IN")).isEqualTo("Namaste");
        assertThat(ReasoningText.speakable("<think>only thinking</think>", "hi-IN")).isEqualTo(FALLBACK.apology("hi-IN"));
        assertThat(ReasoningText.speakable("<thinking>cut off", "en-IN")).isEqualTo(FALLBACK.apology("en-IN"));
        assertThat(ReasoningText.speakable("   ", "xx-XX")).isEqualTo(FALLBACK.apology("en-IN"));
        assertThat(ReasoningText.speakable(null, null)).isEqualTo(FALLBACK.apology("en-IN"));
    }
}
