package com.example.g_one.agent.mediation;

import com.example.g_one.GOneProperties;
import com.example.g_one.agent.llm.ChatClient;
import com.example.g_one.core.Language;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in live translation comparison. Never runs in CI: the class name does not match surefire's patterns and it needs
 * {@code G_ONE_LIVE_BAKEOFF=1}. Run with {@code -Dtest=LiveTranslationBakeoff}. Capped at {@value #MAX_SARVAM_CHARS}
 * Sarvam characters and {@value #MAX_GROQ} Groq requests, paced under {@value #TPM} tokens a minute. All lines are synthetic.
 * Set {@code G_ONE_BAKEOFF_OUT} to the output directory.
 */
@EnabledIfEnvironmentVariable(named = "G_ONE_LIVE_BAKEOFF", matches = "1")
class LiveTranslationBakeoff {
    static final int MAX_SARVAM_CHARS = 8_000;
    static final int MAX_GROQ = 40;
    static final int TPM = 6_000;
    private static final long DEADLINE_MS = 510_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final String BIG = "openai/gpt-oss-120b";
    private static final String SMALL = "openai/gpt-oss-20b";
    private static final Map<String, String> LABEL = Map.of("en-IN", "English", "hi-IN", "Hindi", "hi-en", "Hinglish",
            "bn-IN", "Bengali", "bn-en", "Benglish", "kn-IN", "Kannada", "ta-IN", "Tamil");

    private record Pair(String from, String to, String text) {}

    private record Call(String text, long ms, int tokens, String error) {}

    private static final List<Pair> PAIRS = List.of(
            new Pair("en-IN", "kn-IN", "I will buy forty quintal at eighteen hundred rupees per quintal."),
            new Pair("kn-IN", "en-IN", "ನಾನು ಐವತ್ತು ಕ್ವಿಂಟಾಲ್ ಈರುಳ್ಳಿಯನ್ನು ಹದಿನೆಂಟು ನೂರು ರೂಪಾಯಿಗೆ ಕೊಡುತ್ತೇನೆ."),
            new Pair("en-IN", "bn-IN", "Pay on delivery, pickup tomorrow morning from the Nashik mandi, thirty quintal only."),
            new Pair("bn-IN", "en-IN", "আমি চল্লিশ কুইন্টাল পেঁয়াজ আঠারোশো টাকা কুইন্টালে বিক্রি করব।"),
            new Pair("en-IN", "kn-IN", "Please scan the QR code and enter your UPI PIN to receive two thousand five hundred rupees."),
            new Pair("kn-IN", "en-IN", "ಹುಬ್ಬಳ್ಳಿ ಮಾರುಕಟ್ಟೆಯಲ್ಲಿ ಇವತ್ತು ಬೆಲೆ ಎರಡು ಸಾವಿರದ ಐನೂರು ರೂಪಾಯಿ."),
            new Pair("hi-IN", "kn-IN", "मैं बीस क्विंटल प्याज़ दो हज़ार दो सौ रुपये क्विंटल पर लूँगा, कल सुबह उठाऊँगा।"),
            new Pair("kn-IN", "hi-IN", "ಮೂವತ್ತು ಕ್ವಿಂಟಾಲ್ ಟೊಮೆಟೊ ನಾಳೆ ಬೆಳಿಗ್ಗೆ ಹತ್ತು ಗಂಟೆಗೆ ಸಿದ್ಧ."),
            new Pair("hi-IN", "bn-IN", "सोमवार को पच्चीस क्विंटल आलू, पंद्रह सौ रुपये क्विंटल, भुगतान डिलीवरी पर।"),
            new Pair("bn-IN", "hi-IN", "নাসিক থেকে পঞ্চাশ কুইন্টাল পেঁয়াজ বারোশো টাকায় নেব, কাল সকালে গাড়ি পাঠাব।"),
            new Pair("hi-IN", "en-IN", "मुझे आठ सौ पचास रुपये क्विंटल से कम नहीं चाहिए, तीस क्विंटल है।"),
            new Pair("en-IN", "hi-IN", "I can pay twenty two thousand rupees advance after pickup on the fifteenth of November."),
            new Pair("hi-en", "kn-IN", "Bhaiya atharah sau rupaye quintal denge, chalis quintal hai, pickup kal subah."),
            new Pair("hi-en", "en-IN", "Paanch hazaar advance bhej do, phir maal uthayenge."),
            new Pair("bn-en", "kn-IN", "Ami chollish quintal peyaj nebo, atharo sho taka quintal, kal shokale pickup."),
            new Pair("bn-en", "hi-IN", "Panch hajar taka advance dite hobe, QR code scan korun."),
            new Pair("en-IN", "bn-IN", "Seventy five kilos of rice at forty two rupees per kilo, pay on delivery."),
            new Pair("bn-IN", "kn-IN", "দুই হাজার তিনশো টাকা কুইন্টাল, পঁচিশ কুইন্টাল ধান, সোমবার সকালে নেব।"),
            new Pair("kn-IN", "bn-IN", "ನಲವತ್ತೈದು ಕ್ವಿಂಟಾಲ್ ಅಕ್ಕಿ ಎರಡು ಸಾವಿರದ ನಾಲ್ಕುನೂರು ರೂಪಾಯಿಗೆ, ಡೆಲಿವರಿಯಾದ ಮೇಲೆ ಹಣ."),
            new Pair("en-IN", "kn-IN", "The Hubli mandi rate is one thousand nine hundred and fifty rupees today, and sixty quintal is ready."));

    private final long started = System.currentTimeMillis();
    private int groqCalls;
    private int sarvamChars;
    private long groqTokensSleepUntil;
    private final String groqKey = firstEnv("G_ONE_LLM_API_KEY", "GROQ_API_KEY");
    private final String sarvamKey = System.getenv("SARVAM_API_KEY");

    @Test
    void compare() throws Exception {
        Path out = Path.of(System.getenv().getOrDefault("G_ONE_BAKEOFF_OUT", "target"));
        Files.createDirectories(out);
        List<String> engines = List.of("sarvam-translate:v1", "mayura:v1", "mayura:auto", BIG, SMALL);
        Map<String, List<Call>> byEngine = new LinkedHashMap<>();
        engines.forEach(e -> byEngine.put(e, new ArrayList<>(Collections.nCopies(PAIRS.size(), (Call) null))));
        StringBuilder review = new StringBuilder();

        for (int i = 0; i < PAIRS.size(); i++) {
            Pair p = PAIRS.get(i);
            byEngine.get("sarvam-translate:v1").set(i, sarvam(p, "sarvam-translate:v1", "formal", false));
            byEngine.get("mayura:v1").set(i, sarvam(p, "mayura:v1", p.to().endsWith("-en") ? "code-mixed" : "modern-colloquial", false));
            if (p.from().endsWith("-en")) byEngine.get("mayura:auto").set(i, sarvam(p, "mayura:v1", "modern-colloquial", true));
        }
        // Language models on every other pair to stay inside the Groq cap.
        for (int i = 0; i < PAIRS.size() && withinTime(); i += 2) {
            Pair p = PAIRS.get(i);
            byEngine.get(BIG).set(i, groqTranslate(BIG, p));
            byEngine.get(SMALL).set(i, groqTranslate(SMALL, p));
        }
        // Back-translation of the Mayura output by a different vendor's model, into English, checked with the guard.
        Map<Integer, Call> back = new LinkedHashMap<>();
        for (int i = 0; i < PAIRS.size() && back.size() < 14 && withinTime(); i++) {
            if (i % 3 == 2) continue;
            Call m = byEngine.get("mayura:v1").get(i);
            if (m == null || m.text() == null) continue;
            back.put(i, groqBack(m.text(), PAIRS.get(i).to()));
        }
        String tokens = measureTokens();

        StringBuilder table = new StringBuilder(String.format("%-22s %5s %8s %8s %6s %s%n", "engine", "n", "pass", "p50 ms", "p95 ms", "errors"));
        for (String engine : engines) {
            List<Call> calls = byEngine.get(engine);
            int n = 0, pass = 0, errors = 0;
            List<Long> ms = new ArrayList<>();
            for (int i = 0; i < calls.size(); i++) {
                Call c = calls.get(i);
                if (c == null) continue;
                if (c.text() == null) {
                    errors++;
                    continue;
                }
                n++;
                ms.add(c.ms());
                if (guardOk(PAIRS.get(i), c.text())) pass++;
            }
            Collections.sort(ms);
            table.append(String.format("%-22s %5d %8s %8d %6d %d%n", engine, n, n == 0 ? "-" : pass + "/" + n, pct(ms, 50), pct(ms, 95), errors));
        }
        int backN = 0, backPass = 0;
        for (Map.Entry<Integer, Call> e : back.entrySet()) {
            Call c = e.getValue();
            if (c == null || c.text() == null) continue;
            backN++;
            Pair p = PAIRS.get(e.getKey());
            if (NumberGuard.check(p.text(), p.from(), c.text(), "en-IN").ok()) backPass++;
        }
        table.append(String.format("%nback-translation of mayura by %s: numbers match source %d/%d%n", BIG, backPass, backN));
        table.append(String.format("sarvam chars this run: %d; groq requests: %d%n", sarvamChars, groqCalls));
        table.append(tokens);

        for (int i = 0; i < PAIRS.size(); i++) {
            Pair p = PAIRS.get(i);
            review.append(String.format("#%d  %s -> %s%nSOURCE: %s%n", i + 1, LABEL.get(p.from()), LABEL.get(p.to()), p.text()));
            for (String engine : engines) {
                Call c = byEngine.get(engine).get(i);
                if (c == null) continue;
                review.append(String.format("%-20s [%s] %s%n", engine, c.text() == null ? "ERROR " + c.error() : guardOk(p, c.text()) ? "numbers-ok" : "NUMBER-MISMATCH",
                        c.text() == null ? "" : c.text()));
            }
            Call b = back.get(i);
            if (b != null && b.text() != null) review.append("back to English (120b): ").append(b.text()).append('\n');
            review.append('\n');
        }
        Files.writeString(out.resolve("table.txt"), table.toString(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("pairs.txt"), review.toString(), StandardCharsets.UTF_8);
        System.out.println(table);
    }

    private boolean guardOk(Pair p, String text) {
        return NumberGuard.check(p.text(), p.from(), text, p.to()).ok();
    }

    private boolean withinTime() {
        return System.currentTimeMillis() - started < DEADLINE_MS;
    }

    private static long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int i = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        return sorted.get(Math.max(0, i));
    }

    private Call sarvam(Pair p, String model, String mode, boolean auto) throws Exception {
        if (sarvamChars + p.text().length() > MAX_SARVAM_CHARS) return new Call(null, 0, 0, "char-cap");
        sarvamChars += p.text().length();
        ObjectNode body = JSON.createObjectNode();
        body.put("input", p.text());
        body.put("source_language_code", auto ? "auto" : wire(p.from()));
        body.put("target_language_code", wire(p.to()));
        body.put("model", model);
        body.put("mode", mode);
        body.put("numerals_format", "international");
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.sarvam.ai/translate"))
                .timeout(Duration.ofSeconds(15)).header("api-subscription-key", sarvamKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        long t = System.nanoTime();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long ms = (System.nanoTime() - t) / 1_000_000;
        if (r.statusCode() != 200) return new Call(null, ms, 0, "HTTP " + r.statusCode());
        return new Call(JSON.readTree(r.body()).path("translated_text").asText(), ms, 0, null);
    }

    private static String wire(String code) {
        return code.endsWith("-en") ? code.substring(0, 2) + "-IN" : code;
    }

    private Call groqTranslate(String model, Pair p) throws Exception {
        String system = "Translate the user's spoken line from " + LABEL.get(p.from()) + " to " + LABEL.get(p.to())
                + " naturally, in its native script. Keep every number, place and crop name exact. Reply with the translation only.";
        return groq(model, system, p.text(), 500);
    }

    private Call groqBack(String text, String from) throws Exception {
        return groq(BIG, "Translate the user's line from " + LABEL.get(from) + " to English literally. Keep every number exact. Reply with the translation only.", text, 500);
    }

    private Call groq(String model, String system, String user, int maxTokens) throws Exception {
        if (groqCalls >= MAX_GROQ) return new Call(null, 0, 0, "groq-cap");
        long wait = groqTokensSleepUntil - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        groqCalls++;
        ObjectNode body = JSON.createObjectNode().put("model", model).put("temperature", 0.1).put("max_tokens", maxTokens).put("reasoning_effort", "low");
        body.putArray("messages").addObject().put("role", "system").put("content", system);
        ((com.fasterxml.jackson.databind.node.ArrayNode) body.get("messages")).addObject().put("role", "user").put("content", user);
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + groqKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        long t = System.nanoTime();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long ms = (System.nanoTime() - t) / 1_000_000;
        if (r.statusCode() != 200) {
            groqTokensSleepUntil = System.currentTimeMillis() + 1500;
            return new Call(null, ms, 0, "HTTP " + r.statusCode());
        }
        JsonNode root = JSON.readTree(r.body());
        int tokens = root.path("usage").path("total_tokens").asInt(600);
        groqTokensSleepUntil = System.currentTimeMillis() + tokens * 60_000L / TPM;
        String text = root.path("choices").path(0).path("message").path("content").asText("").strip();
        return new Call(text.isEmpty() ? null : text, ms, tokens, text.isEmpty() ? "empty" : null);
    }

    private String measureTokens() throws Exception {
        if (!withinTime() || groqCalls + 6 > MAX_GROQ) return "tokens measurement skipped (cap or deadline)\n";
        GOneProperties props = new GOneProperties(List.of("a:A:v", "b:B:v"), "", null, null, null);
        TurnReader reader = new TurnReader(new ChatClient(props, JSON), JSON);
        String[][] turns = {{"I will take forty quintal at eighteen hundred rupees, pay on delivery.", "buyer", "English", "Kannada"},
                {"ಐವತ್ತು ಕ್ವಿಂಟಾಲ್ ಈರುಳ್ಳಿ ಇದೆ, ಎರಡು ಸಾವಿರ ಕೊಡಿ.", "farmer", "Kannada", "English"},
                {"Dui hajar taka quintal, kal shokale nebo.", "buyer", "Benglish", "Kannada"}};
        int combinedP = 0, combinedC = 0, extractP = 0, extractC = 0;
        for (String[] t : turns) {
            String combined = usage(reader.combinedPrompt(), input(t, true));
            String extract = usage(reader.extractPrompt(), input(t, false));
            if (combined == null || extract == null) return "tokens measurement skipped (provider unavailable)\n";
            combinedP += Integer.parseInt(combined.split(",")[0]);
            combinedC += Integer.parseInt(combined.split(",")[1]);
            extractP += Integer.parseInt(extract.split(",")[0]);
            extractC += Integer.parseInt(extract.split(",")[1]);
        }
        return String.format("mediation tokens per turn on %s (mean of 3): combined prompt=%d completion=%d total=%d; extract-only prompt=%d completion=%d total=%d%n",
                measuredModel, combinedP / 3, combinedC / 3, (combinedP + combinedC) / 3, extractP / 3, extractC / 3, (extractP + extractC) / 3);
    }

    private String measuredModel = BIG;

    private static String input(String[] t, boolean withTo) {
        ObjectNode input = JSON.createObjectNode();
        input.put("speaker", t[1]);
        input.put("fromLanguage", t[2]);
        if (withTo) input.put("toLanguage", t[3]);
        input.put("awaiting", "none");
        input.putArray("recent");
        input.put("turn", t[0]);
        return input.toString();
    }

    private String usage(String system, String user) throws Exception {
        for (String model : List.of(BIG, SMALL)) {
            long wait = groqTokensSleepUntil - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            if (groqCalls >= MAX_GROQ) return null;
            groqCalls++;
            ObjectNode body = JSON.createObjectNode().put("model", model).put("temperature", 0.1).put("max_tokens", 900).put("reasoning_effort", "low");
            var messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", system);
            messages.addObject().put("role", "user").put("content", user);
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + groqKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) continue;
            JsonNode usage = JSON.readTree(r.body()).path("usage");
            groqTokensSleepUntil = System.currentTimeMillis() + usage.path("total_tokens").asInt(600) * 60_000L / TPM;
            measuredModel = model;
            return usage.path("prompt_tokens").asInt() + "," + usage.path("completion_tokens").asInt();
        }
        return null;
    }

    private static String firstEnv(String... names) {
        for (String n : names) {
            String v = System.getenv(n);
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}
