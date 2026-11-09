package com.example.g_one.agent.mediation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.GOneProperties;
import com.example.g_one.agent.llm.ChatClient;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegRole;
import com.example.g_one.fakes.FakeLlm;
import com.example.g_one.speech.SarvamTranslator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Sarvam-first translation and the number guard, against local fakes. Nothing leaves the machine. */
class TurnTranslatorTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Language EN = new Language("en-IN", "English", "Kaveri");
    private static final Language KN = new Language("kn-IN", "Kannada", "Saanvi");
    private static final String SOURCE = "eighteen hundred rupees per quintal";
    private static final String GOOD = "ಹದಿನೆಂಟು ನೂರು ರೂಪಾಯಿ ಪ್ರತಿ ಕ್ವಿಂಟಾಲ್";
    private static final String WRONG = "ಹದಿನೈದು ನೂರು ರೂಪಾಯಿ ಪ್ರತಿ ಕ್ವಿಂಟಾಲ್";
    private static final String GOOD_JSON = "{\"translation\":\"" + GOOD + "\"}";
    private static final String WRONG_JSON = "{\"translation\":\"" + WRONG + "\"}";
    private static final String NO_OFFER = "{\"status\":\"none\"}";

    private final Deque<String> sarvamReplies = new ArrayDeque<>();
    private final AtomicInteger sarvamCalls = new AtomicInteger();
    private HttpServer sarvamServer;
    private FakeLlm llm;
    private GOneProperties props;
    private ChatClient chat;

    @BeforeEach
    void start() throws IOException {
        sarvamServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sarvamServer.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            sarvamCalls.incrementAndGet();
            String reply;
            synchronized (sarvamReplies) {
                reply = sarvamReplies.isEmpty() ? null : sarvamReplies.pop();
            }
            int status = reply == null ? 500 : 200;
            byte[] body = (reply == null ? "{}" : "{\"translated_text\":\"" + reply + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        sarvamServer.start();
        llm = new FakeLlm();
        props = new GOneProperties(List.of("a:A:v", "b:B:v"), "", null, null,
                new GOneProperties.Llm(llm.baseUrl(), FakeLlm.KEY, "fake-model", 5),
                new GOneProperties.Translate("auto", "http://127.0.0.1:" + sarvamServer.getAddress().getPort(), "k", "mayura:v1",
                        "modern-colloquial", 2));
        chat = new ChatClient(props, JSON);
    }

    @AfterEach
    void stop() {
        sarvamServer.stop(0);
        llm.close();
    }

    private TurnTranslator translator() {
        return new TurnTranslator(chat, JSON, new SarvamTranslator(props, JSON), props);
    }

    private void sarvamSays(String text) {
        synchronized (sarvamReplies) {
            sarvamReplies.add(text);
        }
    }

    @Test
    void aGoodSarvamTranslationNeedsNoModel() {
        sarvamSays(GOOD);
        TurnTranslator.Result r = translator().translate(SOURCE, EN, KN);
        assertThat(r.status()).isEqualTo(TurnTranslator.Status.OK);
        assertThat(r.text()).isEqualTo(GOOD);
        assertThat(r.via()).isEqualTo("sarvam");
        assertThat(llm.requests()).isEmpty();
    }

    @Test
    void aChangedPriceGetsOneTryOnTheModel() {
        sarvamSays(WRONG);
        llm.replyText(GOOD_JSON);
        TurnTranslator.Result r = translator().translate(SOURCE, EN, KN);
        assertThat(r.status()).isEqualTo(TurnTranslator.Status.OK);
        assertThat(r.text()).isEqualTo(GOOD);
        assertThat(r.via()).isEqualTo("llm");
    }

    @Test
    void aPriceThatStaysWrongIsWithheld() {
        sarvamSays(WRONG);
        llm.replyText(WRONG_JSON);
        TurnTranslator.Result r = translator().translate(SOURCE, EN, KN);
        assertThat(r.status()).isEqualTo(TurnTranslator.Status.WITHHELD);
        assertThat(r.text()).isNull();
    }

    @Test
    void sarvamDownFallsBackToTheModel() {
        llm.replyText(GOOD_JSON);
        TurnTranslator.Result r = translator().translate(SOURCE, EN, KN);
        assertThat(r.ok()).isTrue();
        assertThat(r.via()).isEqualTo("llm");
        assertThat(sarvamCalls).hasValue(2);
    }

    @Test
    void bothDownIsAFailureNotAWithholding() {
        llm.failures.set(5);
        TurnTranslator.Result r = translator().translate(SOURCE, EN, KN);
        assertThat(r.status()).isEqualTo(TurnTranslator.Status.FAILED);
    }

    @Test
    void aPairSarvamCannotRouteGoesToTheModel() {
        Language hinglish = new Language("hi-en", "Hinglish", "Poorvi");
        Language hindi = new Language("hi-IN", "Hindi", "Nalini");
        llm.replyText("{\"translation\":\"ठीक है\"}");
        assertThat(translator().translate("theek hai", hinglish, hindi).via()).isEqualTo("llm");
        assertThat(sarvamCalls).hasValue(0);
    }

    @Test
    void theExtractionCallCarriesNoTranslationWorkAndTheTranslationComesFromSarvam() {
        sarvamSays(GOOD);
        llm.replyText("{\"offer\":{\"status\":\"clear\",\"price\":1800,\"unit\":\"quintal\",\"commodity\":\"Onion\"},\"caution\":{\"status\":\"none\"},\"reply\":\"none\"}");
        TurnReader reader = new TurnReader(chat, JSON, translator());

        TurnReader.Reading read = reader.read(LegRole.BUYER, SOURCE, EN, KN, List.of(), "none");

        assertThat(read.withheld()).isFalse();
        assertThat(read.translation()).isEqualTo(GOOD);
        assertThat(read.offer().price()).isEqualByComparingTo("1800");
        String sent = llm.requests().get(0).toString();
        assertThat(sent).doesNotContain("\\\"translation\\\"").doesNotContain("toLanguage");
    }

    @Test
    void aFailedExtractionStillDeliversTheTranslation() {
        sarvamSays(GOOD);
        llm.failures.set(5);
        TurnReader.Reading read = new TurnReader(chat, JSON, translator()).read(LegRole.BUYER, SOURCE, EN, KN, List.of(), "none");
        assertThat(read.translation()).isEqualTo(GOOD);
        assertThat(read.offer()).isNull();
    }

    @Test
    void aWithheldTurnCarriesNoTranslation() {
        sarvamSays(WRONG);
        llm.replyText(WRONG_JSON);
        llm.replyText("{\"offer\":" + NO_OFFER + ",\"caution\":{\"status\":\"none\"},\"reply\":\"none\"}");
        TurnReader.Reading read = new TurnReader(chat, JSON, translator()).read(LegRole.BUYER, SOURCE, EN, KN, List.of(), "none");
        assertThat(read.withheld()).isTrue();
        assertThat(read.translation()).isNull();
    }

    @Test
    void theSameCombinedAnswerIsGuardedWithoutSarvam() {
        GOneProperties.Translate off = new GOneProperties.Translate("llm", "http://127.0.0.1:9", "k", "mayura:v1", "formal", 2);
        GOneProperties llmOnly = new GOneProperties(props.languages(), "", null, null, props.llm(), off);
        ChatClient c = new ChatClient(llmOnly, JSON);
        TurnTranslator t = new TurnTranslator(c, JSON, new SarvamTranslator(llmOnly, JSON), llmOnly);
        assertThat(t.usesSarvam()).isFalse();
        llm.replyText(WRONG_JSON.replace("}", ",\"offer\":" + NO_OFFER + ",\"caution\":{\"status\":\"none\"},\"reply\":\"none\"}"));
        llm.replyText(GOOD_JSON);
        TurnReader.Reading read = new TurnReader(c, JSON, t).read(LegRole.BUYER, SOURCE, EN, KN, List.of(), "none");
        assertThat(read.translation()).isEqualTo(GOOD);
        assertThat(sarvamCalls).hasValue(0);
    }
}
