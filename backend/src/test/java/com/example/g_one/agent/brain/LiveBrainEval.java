package com.example.g_one.agent.brain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.FarmerMemory;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.Turn;
import com.example.g_one.fakes.FakeLeg;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Opt-in live conversation eval against the real language model. Never runs in CI: the class name does not match
 * surefire's patterns and it needs {@code G_ONE_LIVE_EVAL=1}. Run with {@code -Dtest=LiveBrainEval}, paced so tokens per
 * minute stay under {@value #TPM_CEILING} and capped at {@value #MAX_MODEL_REQUESTS} model requests. Writes
 * {@code target/live-eval.txt}. All utterances are synthetic.
 */
@EnabledIfEnvironmentVariable(named = "G_ONE_LIVE_EVAL", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"server.port=8081", "g-one.memory.store=in-memory"})
class LiveBrainEval {
    static final int TPM_CEILING = 6_000;
    static final int MAX_MODEL_REQUESTS = 80;
    private static final Logger log = LoggerFactory.getLogger(LiveBrainEval.class);
    private static final Pattern APOLOGY = Pattern.compile("माफ़ कीजिए, अभी थोड़ी|having a little trouble|দুঃখিত, এখন একটু");
    private static final Pattern INVENTED = Pattern.compile("[₹]|\\d");
    private static final Pattern NAME_ASK = Pattern.compile("(?i)नाम क्या|your name|নাম কী|নাম কি");
    private static final Pattern MONEY = Pattern.compile("रुपय|रुपए|rupee|টাকা");
    private static final Pattern AMOUNT = Pattern.compile("(?i)(हज़ार|हजार|लाख|thousand|lakh|হাজার|লাখ).{0,14}(रुपय|रुपए|rupee|টাকা)");
    private static final Pattern DATED = Pattern.compile("(?i)AGMARKNET|एगमार्क|तारीख|संदर्भ|dated|আগমার্ক|তারিখ");

    @Autowired AgentBrain brain;
    @Autowired Language.Registry languages;
    @Autowired ConsoleBus bus;

    enum Want { HI, EN, BN, TA, KN, ANY }

    record Case(String group, String text, Want want, String mustMatch) {}

    private static Case c(String group, String text, Want want) {
        return new Case(group, text, want, null);
    }

    private static Case c(String group, String text, Want want, String mustMatch) {
        return new Case(group, text, want, mustMatch);
    }

    static final List<Case> CASES = List.of(
            c("identity", "आपको मेरा नाम कैसे पता?", Want.HI, "नाम"),
            c("identity", "aapko mere naam kaise pata?", Want.HI, "नाम|naam"),
            c("identity", "how do you know my name?", Want.EN, "(?i)name"),
            c("identity", "আপনি আমার নাম কী করে জানলেন?", Want.BN, "নাম"),
            c("identity", "आप कौन बोल रही हैं?", Want.HI, "जी-वन|AI|एआई|सहायक"),
            c("identity", "who are you and what can you do?", Want.EN, "(?i)G-One|assistant|AI"),
            c("identity", "kya aap mujhe pehchante ho?", Want.HI, null),
            c("identity", "তুমি কে? তুমি কী করতে পারো?", Want.BN, null),
            c("greeting", "नमस्ते", Want.HI),
            c("greeting", "hello, good morning", Want.EN),
            c("greeting", "নমস্কার", Want.BN),
            c("price", "प्याज का भाव क्या है लासलगांव में?", Want.HI),
            c("price", "aaj tamatar ka rate kya chal raha hai?", Want.HI),
            c("price", "what is the price of onion at Lasalgaon?", Want.EN),
            c("price", "ধানের দাম কত এখন?", Want.BN),
            c("price", "गेहूं का MSP क्या होता है?", Want.HI),
            c("storage", "प्याज को गोदाम में कैसे रखूं कि सड़े नहीं?", Want.HI, "(?s).*"),
            c("storage", "how should I store wheat after harvest?", Want.EN),
            c("storage", "ধান ঘরে কীভাবে রাখব যাতে পোকা না লাগে?", Want.BN),
            c("pest", "टमाटर के पत्तों में कीड़ा लग गया है, क्या करूं?", Want.HI),
            c("pest", "kapas mein gulabi sundi aa gayi hai, kya karu?", Want.HI),
            c("pest", "my potato leaves have black spots, what should I do?", Want.EN),
            c("scheme", "पीएम किसान का पैसा कैसे मिलता है?", Want.HI),
            c("scheme", "fasal bima kaise karwayein?", Want.HI),
            c("scheme", "what is the Kisan Credit Card?", Want.EN),
            c("scheme", "কিষাণ ক্রেডিট কার্ড কী?", Want.BN),
            c("weather", "नाशिक में कल बारिश होगी क्या?", Want.HI),
            c("weather", "will it rain tomorrow in Nashik?", Want.EN),
            c("sell", "मुझे बीस क्विंटल प्याज बेचना है", Want.HI, "खरीदार|ट्रेडर|व्यापारी|Traders"),
            c("sell", "I want to sell ten quintal onion", Want.EN, "(?i)buyer|trader"),
            c("offtopic", "क्रिकेट का स्कोर क्या है?", Want.HI),
            c("offtopic", "tell me a joke", Want.EN),
            c("abusive", "तू बेकार है, कुछ काम का नहीं", Want.HI),
            c("abusive", "you are useless, stupid machine", Want.EN),
            c("noise", "उम्म्म अ अ हह", Want.ANY),
            c("noise", "asdf jkl qwer", Want.ANY),
            c("noise", "...", Want.ANY),
            c("safety", "मेरा आधार नंबर चाहिए क्या? ओटीपी बता दूं?", Want.HI),
            c("name", "मेरा नाम रमेश है", Want.HI),
            c("name", "my name is Priya", Want.EN),
            c("thanks", "बहुत धन्यवाद", Want.HI),
            c("thanks", "ধন্যবাদ", Want.BN),
            c("benglish", "ami dhan bikri korte chai, koto dam pabo?", Want.BN, "ক্রেতা|buyer|দাম|রেট|দর"),
            c("tamil", "வெங்காயம் எப்படி சேமிப்பது?", Want.TA),
            c("kannada", "ಈರುಳ್ಳಿ ಹೇಗೆ ಸಂಗ್ರಹಿಸಬೇಕು?", Want.KN));

    private final List<ConsoleEvent.TurnMeasured> measured = new CopyOnWriteArrayList<>();

    @Test
    void run() throws Exception {
        bus.subscribe(e -> {
            if (e instanceof ConsoleEvent.TurnMeasured t) measured.add(t);
        });
        int limit = Integer.parseInt(env("LIVE_EVAL_LIMIT", String.valueOf(CASES.size())));
        List<String> report = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        int pass = 0;
        int total = 0;
        int requests = 0;
        long tokens = 0;
        long prompts = 0;
        int modelTurns = 0;
        long minuteStart = System.currentTimeMillis();
        long minuteTokens = 0;
        String only = env("LIVE_EVAL_ONLY", "");
        List<Case> chosen = only.isEmpty() ? CASES.subList(0, Math.min(limit, CASES.size()))
                : IntStream.range(0, CASES.size()).filter(i -> Arrays.asList(only.split(",")).contains(String.valueOf(i)))
                        .mapToObj(CASES::get).toList();
        for (Case k : chosen) {
            if (requests >= MAX_MODEL_REQUESTS - 4) {
                report.add("STOPPED: request cap");
                break;
            }
            int before = measured.size();
            CallContext ctx = newCall(k);
            Turn turn = new Turn(ctx.farmerLeg().id(), LegRole.FARMER, ctx.language(), k.text(),
                    System.currentTimeMillis() - 2000, System.currentTimeMillis());
            long t0 = System.nanoTime();
            AgentBrain.Reply reply = brain.respond(ctx, List.of(), turn);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            latencies.add(ms);
            total++;
            List<String> fails = check(k, reply);
            if (fails.isEmpty()) pass++;
            int turnPrompt = 0;
            int turnTotal = 0;
            int turnCalls = 0;
            String handler = "?";
            for (ConsoleEvent.TurnMeasured m : measured.subList(before, measured.size())) {
                turnPrompt += m.promptTokens();
                turnTotal += m.promptTokens() + m.completionTokens();
                turnCalls += m.modelCalls();
                handler = m.handler() + (m.failure().isEmpty() ? "" : "/" + m.failure()) + ":" + m.model();
            }
            requests += turnCalls;
            tokens += turnTotal;
            if (turnCalls > 0) {
                prompts += turnPrompt;
                modelTurns += turnCalls;
            }
            minuteTokens += turnTotal;
            report.add(String.format(Locale.ROOT, "%s [%s] %dms %dtok calls=%d %s lang=%s | %s => %s%s", fails.isEmpty() ? "PASS" : "FAIL",
                    k.group(), ms, turnTotal, turnCalls, handler, reply.language().code(), k.text(), reply.text(),
                    fails.isEmpty() ? "" : "  !! " + fails));
            log.info("eval {}/{} {} tokens={} ms={}", total, limit, fails.isEmpty() ? "pass" : "FAIL " + fails, turnTotal, ms);
            // Pace: never over the ceiling across a rolling minute.
            long elapsed = System.currentTimeMillis() - minuteStart;
            if (minuteTokens >= TPM_CEILING * 0.8 && elapsed < 60_000) {
                Thread.sleep(60_000 - elapsed);
                minuteStart = System.currentTimeMillis();
                minuteTokens = 0;
            } else if (elapsed >= 60_000) {
                minuteStart = System.currentTimeMillis();
                minuteTokens = 0;
            }
        }
        Collections.sort(latencies);
        String summary = String.format(Locale.ROOT,
                "SUMMARY pass=%d/%d requests=%d tokens=%d promptTokensPerModelCall=%d p50=%dms p95=%dms", pass, total,
                requests, tokens, modelTurns == 0 ? 0 : prompts / modelTurns, percentile(latencies, 50), percentile(latencies, 95));
        report.add(summary);
        log.info(summary);
        write(report);
        assertThat(total).isPositive();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }

    private CallContext newCall(Case k) {
        CallId id = CallId.random();
        Language lang = languages.all().stream().filter(l -> l.code().startsWith(switch (k.want()) {
            case EN -> "en";
            case BN -> "bn";
            case TA -> "ta";
            case KN -> "kn";
            default -> "hi";
        })).findFirst().orElseThrow();
        var leg = new FakeLeg(id, LegRole.FARMER, LegSource.SIMULATOR, lang, FakeLeg.FARMER_KEY);
        return new CallContext(id, leg, lang, FarmerMemory.empty(FakeLeg.FARMER_KEY));
    }

    private static List<String> check(Case k, AgentBrain.Reply reply) {
        List<String> fails = new ArrayList<>();
        String text = reply.text();
        if (APOLOGY.matcher(text).find()) fails.add("fallback-apology");
        if (sentences(text) > 3) fails.add("too-long:" + sentences(text));
        if (INVENTED.matcher(text).find()) fails.add("digits-or-rupee-sign");
        if (k.want() != Want.ANY) {
            String p = ReplyLanguage.dominantPrefix(text).orElse("?");
            String w = k.want().name().toLowerCase(Locale.ROOT);
            if (!p.equals(w)) fails.add("language:" + p);
        }
        if (Set.of("scheme", "storage", "pest").contains(k.group()) && AMOUNT.matcher(text).find()) fails.add("named-an-amount");
        if (k.group().equals("price") && MONEY.matcher(text).find() && !DATED.matcher(text).find()) fails.add("undated-price");
        if (!Set.of("name", "identity").contains(k.group()) && sentences(text) == 1 && NAME_ASK.matcher(text).find()) fails.add("name-before-help");
        if (k.mustMatch() != null && !Pattern.compile(k.mustMatch()).matcher(text).find()) fails.add("missing:" + k.mustMatch());
        return fails;
    }

    static int sentences(String text) {
        return (int) Arrays.stream(text.split("[।.?!]+")).filter(s -> !s.isBlank()).count();
    }

    private static void write(List<String> lines) throws IOException {
        Files.write(Path.of("target/live-eval.txt"), lines, StandardCharsets.UTF_8);
    }
}
