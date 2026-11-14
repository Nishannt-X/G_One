package com.example.g_one.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Privacy review round 2, B1: every persisted string comes from a closed set; the stated name follows its policy. */
class VocabularyTest {
    private static final Vocabulary VOCAB = Vocabulary.bundled(new ObjectMapper());

    @Test
    void toolsComeFromTheRegisteredNamesOnly() {
        for (String tool : Vocabulary.BUNDLED_TOOLS) assertThat(VOCAB.tool(tool)).isEqualTo(tool);
        for (String planted : List.of("RameshPrivateAddress", "get_mandi_price ", "GET_MANDI_PRICE", "stated_name",
                "9876543210", "", "other_tool")) {
            assertThat(VOCAB.tool(planted)).as(planted).isEqualTo("other");
        }
        assertThat(VOCAB.tool(null)).isEqualTo("other");
        Vocabulary custom = new Vocabulary(VOCAB.deals(), List.of("only_this"), List.of("hi-IN"));
        assertThat(custom.tool("only_this")).isEqualTo("only_this");
        assertThat(custom.tool("get_mandi_price")).isEqualTo("other");
    }

    @Test
    void languagesComeFromTheRegistry() {
        for (String code : Vocabulary.BUNDLED_LANGUAGES) assertThat(VOCAB.language(code)).isEqualTo(code);
        assertThat(VOCAB.language("auto")).isEqualTo("auto");
        for (String planted : List.of("Ramesh-Bhasha", "Hindi", "hi", "xx-IN", "मराठी")) {
            assertThat(VOCAB.language(planted)).as(planted).isEqualTo("other");
        }
    }

    @Test
    void pipelineWordsAreClosedSets() {
        for (String r : List.of("hangup", "assistant_hangup", "farmer_left", "buyer_declined", "deal_agreed",
                "stream_stopped", "error", "evicted", "shutdown")) {
            assertThat(Vocabulary.endReason(r)).isEqualTo(r);
        }
        assertThat(Vocabulary.endReason("IllegalStateException: boom at Ramesh")).isEqualTo("other");
        assertThat(Vocabulary.handler("model")).isEqualTo("model");
        assertThat(Vocabulary.handler("handler-Ramesh")).isEqualTo("other");
        assertThat(Vocabulary.failure("rate_limited")).isEqualTo("rate_limited");
        assertThat(Vocabulary.failure("429 Too Many Requests for Ramesh")).isEqualTo("other");
        assertThat(Vocabulary.languageReason("named")).isEqualTo("named");
        assertThat(Vocabulary.languageReason("reason-Ramesh")).isEqualTo("other");
        assertThat(Vocabulary.outcome("agreed")).isEqualTo("agreed");
        assertThat(Vocabulary.outcome("Ramesh")).isEqualTo("no_terms");
        assertThat(Vocabulary.closed(Vocabulary.SOURCES, "twilio")).isEqualTo("twilio");
        assertThat(Vocabulary.closedOrNull(Vocabulary.WHISPER_KINDS, "secret")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Suresh", "Ramesh Kumar", "D'Souza", "Anil-Kumar", "S. K. Patil", "सुरेश पाटील",
        "সুরেশ মণ্ডল", "சுரேஷ்", "ಸುರೇಶ್ ಗೌಡ", "Mary Ann Jo Lee"})
    void plainNamesAreKept(String name) {
        assertThat(Vocabulary.statedName(name)).contains(name);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Ramesh 9876543210", "Ramesh ९८७", "সুরেশ ৯", "சுரேஷ் ௯", "ಸುರೇಶ್ ೯", "R2D2",
        "one two", "Ramesh ek", "दो", "Suresh সাত", "ஐந்து", "ಎರಡು", "a b c d e",
        "Supercalifragilisticexpialidocious", "ramesh@example.org", "www.ramesh.in", "Ramesh!", "Flat 4B",
        "call me, now", "Ramesh_Kumar", "   "})
    void anythingElseIsLeftOut(String name) {
        assertThat(Vocabulary.statedName(name)).isEmpty();
    }
}
