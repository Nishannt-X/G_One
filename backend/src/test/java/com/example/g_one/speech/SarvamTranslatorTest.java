package com.example.g_one.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.GOneProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Sarvam client against a local JDK HttpServer. Nothing leaves the machine. */
class SarvamTranslatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "test-sarvam-key-not-a-secret";

    private record Reply(int status, String body, String retryAfter) {}

    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            keys.add(exchange.getRequestHeaders().getFirst("api-subscription-key"));
            paths.add(exchange.getRequestURI().getPath());
            Reply r;
            synchronized (replies) {
                r = replies.isEmpty() ? ok("अनुवाद") : replies.pop();
            }
            if (r.retryAfter() != null) exchange.getResponseHeaders().add("Retry-After", r.retryAfter());
            byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(r.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Reply ok(String text) {
        return new Reply(200, "{\"request_id\":\"r\",\"translated_text\":\"" + text + "\",\"source_language_code\":\"en-IN\"}", null);
    }

    private SarvamTranslator translator(String model, String mode) {
        GOneProperties.Translate cfg = new GOneProperties.Translate("auto",
                "http://127.0.0.1:" + server.getAddress().getPort(), KEY, model, mode, 2);
        return new SarvamTranslator(new GOneProperties(List.of("a:A:v", "b:B:v"), "", null, null, null, cfg), JSON);
    }

    private void queue(Reply... rs) {
        synchronized (replies) {
            replies.addAll(List.of(rs));
        }
    }

    @Test
    void sendsTheDocumentedRequestShapeAndKeyHeader() {
        assertThat(translator("mayura:v1", "modern-colloquial").translate("fifty quintals", "en-IN", "kn-IN")).isEqualTo("अनुवाद");

        JsonNode sent = requests.get(0);
        assertThat(paths).containsExactly("/translate");
        assertThat(keys).containsExactly(KEY);
        assertThat(sent.path("input").asText()).isEqualTo("fifty quintals");
        assertThat(sent.path("source_language_code").asText()).isEqualTo("en-IN");
        assertThat(sent.path("target_language_code").asText()).isEqualTo("kn-IN");
        assertThat(sent.path("model").asText()).isEqualTo("mayura:v1");
        assertThat(sent.path("mode").asText()).isEqualTo("modern-colloquial");
        assertThat(sent.path("numerals_format").asText()).isEqualTo("international");
    }

    @Test
    void hinglishAndBenglishMapToTheBaseLanguageAndCodeMixedForAListener() {
        SarvamTranslator t = translator("mayura:v1", "modern-colloquial");
        t.translate("Pachas quintal", "hi-en", "bn-IN");
        t.translate("Fifty quintals", "en-IN", "bn-en");

        assertThat(requests.get(0).path("source_language_code").asText()).isEqualTo("hi-IN");
        assertThat(requests.get(0).path("mode").asText()).isEqualTo("modern-colloquial");
        assertThat(requests.get(1).path("target_language_code").asText()).isEqualTo("bn-IN");
        assertThat(requests.get(1).path("mode").asText()).isEqualTo("code-mixed");
    }

    @Test
    void theFormalOnlyModelAlwaysGetsFormal() {
        translator("sarvam-translate:v1", "modern-colloquial").translate("x", "en-IN", "bn-en");
        assertThat(requests.get(0).path("mode").asText()).isEqualTo("formal");
    }

    @Test
    void aPairThatMapsToOneLanguageHasNoRoute() {
        SarvamTranslator t = translator("mayura:v1", "formal");
        assertThat(t.supports("hi-en", "hi-IN")).isFalse();
        assertThat(t.supports("xx-IN", "hi-IN")).isFalse();
        assertThatThrownBy(() -> t.translate("x", "hi-en", "hi-IN")).isInstanceOf(SarvamTranslator.Failed.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void retriesOnceOn429ThenSucceeds() {
        queue(new Reply(429, "{\"error\":{\"code\":\"rate\"}}", "0"), ok("ठीक"));
        assertThat(translator("mayura:v1", "formal").translate("x", "en-IN", "hi-IN")).isEqualTo("ठीक");
        assertThat(requests).hasSize(2);
    }

    @Test
    void retriesOnceOn500AndGivesUpAfterTheSecond() {
        queue(new Reply(500, "{}", null), new Reply(500, "{}", null));
        assertThatThrownBy(() -> translator("mayura:v1", "formal").translate("x", "en-IN", "hi-IN"))
                .isInstanceOfSatisfying(SarvamTranslator.Failed.class, f -> assertThat(f.status()).isEqualTo(500));
        assertThat(requests).hasSize(2);
    }

    @Test
    void aClientErrorIsNotRetriedAndIsAFailure() {
        queue(new Reply(403, "{\"error\":{\"code\":\"bad_key\"}}", null));
        assertThatThrownBy(() -> translator("mayura:v1", "formal").translate("x", "en-IN", "hi-IN"))
                .isInstanceOf(SarvamTranslator.Failed.class);
        assertThat(requests).hasSize(1);
    }

    @Test
    void sanitisesMarkupAndRejectsAnEmptyOrEssayAnswer() {
        queue(ok("**नमस्ते**   `जी`"), ok(" "), ok("x".repeat(500)));
        SarvamTranslator t = translator("mayura:v1", "formal");
        assertThat(t.translate("hello", "en-IN", "hi-IN")).isEqualTo("नमस्ते जी");
        assertThatThrownBy(() -> t.translate("hello", "en-IN", "hi-IN")).isInstanceOf(SarvamTranslator.Failed.class);
        assertThatThrownBy(() -> t.translate("hello", "en-IN", "hi-IN")).isInstanceOf(SarvamTranslator.Failed.class);
    }

    @Test
    void anUnconfiguredClientNeverCalls() {
        GOneProperties.Translate cfg = new GOneProperties.Translate("auto", "http://127.0.0.1:9", "", "mayura:v1", "formal", 2);
        SarvamTranslator t = new SarvamTranslator(new GOneProperties(List.of("a:A:v", "b:B:v"), "", null, null, null, cfg), JSON);
        assertThat(t.configured()).isFalse();
        assertThatThrownBy(() -> t.translate("x", "en-IN", "hi-IN")).isInstanceOf(SarvamTranslator.Failed.class);
    }
}
