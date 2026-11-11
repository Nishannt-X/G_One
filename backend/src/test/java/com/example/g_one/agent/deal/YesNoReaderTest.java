package com.example.g_one.agent.deal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.GOneProperties;
import com.example.g_one.agent.deal.ReplyGuard.Reading;
import com.example.g_one.agent.llm.ChatClient;
import com.example.g_one.agent.llm.ChatReply;
import com.example.g_one.fakes.FakeLlm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The layered reader against {@link FakeLlm}: words first, one small model call only when they settle nothing. */
class YesNoReaderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SAVE_Q = "क्या हम आगे के लिए इस बातचीत की मुख्य बातें याद रख सकते हैं?";

    private FakeLlm llm;
    private YesNoReader reader;

    @BeforeEach
    void start() throws Exception {
        llm = new FakeLlm();
        reader = new YesNoReader(new ChatClient(props(llm.baseUrl()), JSON));
    }

    @AfterEach
    void stop() {
        llm.close();
    }

    private static GOneProperties props(String base) {
        return new GOneProperties(List.of("hi-IN:Hindi:Nalini"), "", null, null,
                new GOneProperties.Llm(base, FakeLlm.KEY, "fake-model", 5));
    }

    @Test
    void wordsTheLexiconKnowsNeverReachTheModel() {
        assertThat(reader.read(SAVE_Q, "रख सकते हैं")).isEqualTo(Reading.YES);
        assertThat(reader.read(SAVE_Q, "नहीं")).isEqualTo(Reading.NO);
        assertThat(reader.read(SAVE_Q, "हाँ अगर पैसा मिले")).as("vetoed: never asked").isEqualTo(Reading.UNCLEAR);
        assertThat(reader.read(SAVE_Q, "पता नहीं")).isEqualTo(Reading.UNCLEAR);
        assertThat(llm.requests()).isEmpty();
    }

    @Test
    void anUnknownReplyGetsOneSmallCallAndItsOneWord() {
        llm.replyText("YES");
        assertThat(reader.read(SAVE_Q, "आप याद रखिएगा, हमारे काम आएगा")).isEqualTo(Reading.YES);
        assertThat(llm.requests()).hasSize(1);
        JsonNode request = llm.requests().get(0);
        assertThat(request.has("tools")).isFalse();
        assertThat(request.path("max_tokens").asInt()).isLessThanOrEqualTo(150);
        assertThat(request.path("temperature").asDouble()).isZero();
        assertThat(request.path("messages").get(1).path("content").asText()).contains(SAVE_Q).contains("हमारे काम आएगा");

        assertThat(reader.read(SAVE_Q, "उसकी कोई ज़रूरत लगती है क्या आपको भाई"))
                .as("a question word vetoes before any call").isEqualTo(Reading.UNCLEAR);
        llm.replyText("NO");
        assertThat(reader.read(SAVE_Q, "यह सब रिकॉर्ड में रखना ठीक नहीं लगता")).isEqualTo(Reading.UNCLEAR)
                .as("yes and no words together: vetoed, no call");
        assertThat(reader.read(SAVE_Q, "रिकॉर्ड करने से हमें परहेज़ है")).isEqualTo(Reading.NO);
        assertThat(llm.requests()).hasSize(2);
    }

    @Test
    void anythingButOneClearWordIsUnclear() {
        for (String answer : List.of("YES or NO", "I think so", "", "UNCLEAR")) {
            llm.replyText(answer.isEmpty() ? " " : answer);
            assertThat(reader.read(SAVE_Q, "आप याद रखिएगा भाई साहब")).as(answer).isEqualTo(Reading.UNCLEAR);
        }
        assertThat(YesNoReader.parse("yes.")).isEqualTo(Reading.YES);
        assertThat(YesNoReader.parse("Answer: NO")).isEqualTo(Reading.NO);
    }

    @Test
    void aFailingModelFailsClosedWithoutARetry() {
        llm.failures.set(5);
        assertThat(reader.read(SAVE_Q, "आप याद रखिएगा भाई साहब")).isEqualTo(Reading.UNCLEAR);
        assertThat(llm.requests()).hasSize(1);
    }

    @Test
    void theOfflineDemoAndAMissingModelReadWordsOnly() {
        YesNoReader offline = new YesNoReader(new ChatClient(props(llm.baseUrl()), JSON,
                request -> new ChatReply("YES", List.of(), "stop", new ChatReply.Usage("demo", 0, 0, 0))));
        assertThat(offline.read(SAVE_Q, "आप याद रखिएगा भाई साहब")).isEqualTo(Reading.UNCLEAR);
        assertThat(new YesNoReader(new ChatClient(props(""), JSON)).read(SAVE_Q, "आप याद रखिएगा")).isEqualTo(Reading.UNCLEAR);
        assertThat(new YesNoReader(null).read(SAVE_Q, "हाँ कोई बात नहीं")).isEqualTo(Reading.YES);
        assertThat(llm.requests()).isEmpty();
    }
}
