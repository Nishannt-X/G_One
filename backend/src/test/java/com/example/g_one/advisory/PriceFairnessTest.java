package com.example.g_one.advisory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PriceFairnessTest {
    private final PriceFairness fairness = new PriceFairness();
    private final PriceFairnessTool tool = new PriceFairnessTool();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void loadsAllBundledCrops() {
        assertThat(fairness.allCrops().size()).isGreaterThanOrEqualTo(23);
    }

    @Test
    void matchesOnionByAliases() {
        assertThat(fairness.findCrop("pyaz")).isPresent();
        assertThat(fairness.findCrop("pyaaz")).isPresent();
        assertThat(fairness.findCrop("kanda")).isPresent();
        assertThat(fairness.findCrop("प्याज")).isPresent();
        assertThat(fairness.findCrop("onion").get().modal()).isEqualTo(4500);
    }

    @Test
    void evaluatesGreenWhenQuotedAboveBenchmark() {
        var eval = fairness.evaluate("onion", 4800);
        assertThat(eval.found()).isTrue();
        assertThat(eval.status()).isEqualTo(PriceFairness.Status.GREEN);
        assertThat(eval.gapRupeesPerQuintal()).isEqualTo(300.0);
        assertThat(eval.whisperTextEn()).contains("Favorable offer");
        assertThat(eval.whisperTextEn()).contains("indicative sample");
        // Hindi text must have numbers as words
        assertThat(eval.whisperTextHi()).contains("रुपये");
        assertThat(eval.whisperTextHi()).contains("सांकेतिक नमूना");
        assertThat(eval.whisperTextHi()).doesNotContain("4800");
    }

    @Test
    void evaluatesAmberWhenQuotedSlightlyBelowBenchmark() {
        var eval = fairness.evaluate("onion", 4200);
        assertThat(eval.found()).isTrue();
        assertThat(eval.status()).isEqualTo(PriceFairness.Status.AMBER);
        assertThat(eval.gapRupeesPerQuintal()).isEqualTo(-300.0);
        assertThat(eval.whisperTextEn()).contains("Negotiable offer");
    }

    @Test
    void evaluatesRedWhenQuotedFarBelowBenchmark() {
        var eval = fairness.evaluate("onion", 3500);
        assertThat(eval.found()).isTrue();
        assertThat(eval.status()).isEqualTo(PriceFairness.Status.RED);
        assertThat(eval.gapRupeesPerQuintal()).isEqualTo(-1000.0);
        assertThat(eval.whisperTextEn()).contains("Low offer");
    }

    @Test
    void handlesCropWithMsp() {
        var eval = fairness.evaluate("wheat", 2600);
        assertThat(eval.found()).isTrue();
        assertThat(eval.msp()).isNotNull();
        assertThat(eval.msp()).isEqualTo(2585);
        assertThat(eval.whisperTextEn()).contains("MSP: ₹2585");
        assertThat(eval.whisperTextHi()).contains("एमएसपी");
    }

    @Test
    void enforcesHonestWording() {
        var eval = fairness.evaluate("paddy", 2300);
        String en = eval.whisperTextEn().toLowerCase();
        String hi = eval.whisperTextHi().toLowerCase();
        for (String forbidden : new String[]{"verified", "real time", "instant", "blocks scams", "guaranteed"}) {
            assertThat(en).doesNotContain(forbidden);
            assertThat(hi).doesNotContain(forbidden);
        }
        assertThat(eval.whisperTextEn()).contains("indicative sample");
        assertThat(eval.whisperTextHi()).contains("सांकेतिक नमूना");
    }

    @Test
    void handlesUnknownCropGracefully() {
        var eval = fairness.evaluate("dragonfruit", 12000);
        assertThat(eval.found()).isFalse();
        assertThat(eval.status()).isEqualTo(PriceFairness.Status.UNKNOWN);
        assertThat(eval.whisperTextEn()).contains("No indicative sample price found");
    }

    @Test
    void toolInvocationWorks() throws Exception {
        var args = JSON.createObjectNode()
                .put("commodity", "गेहूं")
                .put("price", 2500);
        ToolResult res = tool.invoke(args, null);
        assertThat(res.ok()).isTrue();
        var json = JSON.readTree(res.content());
        assertThat(json.get("found").asBoolean()).isTrue();
        assertThat(json.get("commodity").asText()).isEqualTo("Wheat");
        assertThat(json.get("status").asText()).isEqualTo("AMBER");
        assertThat(json.get("whisperTextHi").asText()).contains("गेहूं");
    }
}
