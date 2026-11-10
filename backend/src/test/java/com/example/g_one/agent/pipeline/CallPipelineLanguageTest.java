package com.example.g_one.agent.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.GOneProperties;
import com.example.g_one.agent.llm.ReasoningText;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegRegistry;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.fakes.FakeSpeechToText;
import com.example.g_one.fakes.FakeTextToSpeech;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A farmer leg with no language yet, against the fakes. Steps run on a manual executor, so the order is the test's. */
class CallPipelineLanguageTest {
    private final AtomicLong clock = new AtomicLong(1_762_600_000_000L);
    private final ManualExecutor executor = new ManualExecutor();
    private final ScriptedBrain brain = new ScriptedBrain();
    private final FakeSpeechToText stt = new FakeSpeechToText(clock::get);
    private final FakeTextToSpeech tts = new FakeTextToSpeech();
    private final ConsoleBus bus = new ConsoleBus();
    private final List<ConsoleEvent> events = new CopyOnWriteArrayList<>();
    private final LegRegistry registry = new LegRegistry();
    private FakeLeg leg;

    @BeforeEach
    void setUp() {
        bus.subscribe(events::add);
        GOneProperties props = new GOneProperties(
                List.of("hi-IN:Hindi:Nalini", "en-IN:English:Kaveri", "hi-en:Hinglish:Poorvi", "kn-IN:Kannada:Saanvi"), "",
                new GOneProperties.Agmarknet("http://127.0.0.1:9", "", "", "", "", "", "", 5, 3, 30), null,
                new GOneProperties.Llm("http://127.0.0.1:9", "k", "m", 5));
        new CallPipeline(brain, stt, tts, new InMemoryMemoryStore(), bus, List.of(), executor, clock::get, null, new Language.Registry(props))
                .attach(registry);
        leg = new FakeLeg(CallId.random(), com.example.g_one.core.LegRole.FARMER, com.example.g_one.core.LegSource.SIMULATOR, FakeLeg.ENGLISH, null).pendingLanguage();
        registry.register(leg);
        executor.runAll();
    }

    private void hearAll() {
        for (int i = 0; i < 6; i++) {
            leg.finishPlayback();
            executor.runAll();
        }
    }

    private void say(String text) {
        stt.last().say(text);
        executor.runAll();
    }

    private List<String> spoken() {
        return tts.requests().stream().map(FakeTextToSpeech.Request::text).toList();
    }

    private <T extends ConsoleEvent> List<T> eventsOf(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void aPendingLegHearsTheEnglishQuestionAndNotTheGreeting() {
        assertThat(spoken()).singleElement().asString().startsWith("Hello, this is G-One.").contains("Which language");
        assertThat(tts.requests().get(0).language().code()).isEqualTo("en-IN");
        assertThat(brain.greets).isEmpty();
        assertThat(stt.last().language()).isEqualTo(Language.DETECTING);
        assertThat(eventsOf(ConsoleEvent.CallStarted.class).get(0).language()).isEqualTo("auto");
        assertThat(eventsOf(ConsoleEvent.LegJoined.class).get(0).language()).isEqualTo("auto");
    }

    @Test
    void namingALanguageConfirmsAndGreetsInItWithoutAnAnswer() {
        hearAll();
        say("हिंदी");
        hearAll();

        assertThat(leg.language().code()).isEqualTo("hi-IN");
        assertThat(leg.languagePending()).isFalse();
        assertThat(stt.last().retargets()).extracting(Language::code).containsExactly("hi-IN");
        assertThat(spoken()).containsExactly(spoken().get(0), "ठीक है, हिंदी में बात करते हैं।", ScriptedBrain.GREETING);
        assertThat(brain.greets).singleElement().satisfies(c -> assertThat(c.language().code()).isEqualTo("hi-IN"));
        assertThat(brain.responds).isEmpty();
        assertThat(eventsOf(ConsoleEvent.LanguageChanged.class)).singleElement().satisfies(e -> {
            assertThat(e.from()).isEqualTo("auto");
            assertThat(e.to()).isEqualTo("hi-IN");
            assertThat(e.reason()).isEqualTo("named");
        });
        assertThat(eventsOf(ConsoleEvent.TurnHeard.class)).singleElement().satisfies(t -> assertThat(t.language()).isEqualTo("hi-IN"));
    }

    @Test
    void aSentenceChoosesTheLanguageAndIsAnsweredAfterTheGreeting() {
        hearAll();
        say("मुझे प्याज़ का भाव बताइए");
        hearAll();

        assertThat(spoken()).endsWith(ScriptedBrain.GREETING, ScriptedBrain.replyTo("मुझे प्याज़ का भाव बताइए"));
        assertThat(brain.responds).singleElement().satisfies(r -> {
            assertThat(r.latest().language().code()).isEqualTo("hi-IN");
            assertThat(r.ctx().language().code()).isEqualTo("hi-IN");
            assertThat(r.history()).noneMatch(l -> l.text().equals("मुझे प्याज़ का भाव बताइए"));
        });
    }

    @Test
    void aSentenceThatChoosesTheLanguageIsNotTakenAsTheAnswerToTheNameCheckThatFollows() {
        brain.greetText = "क्या मैं Suresh जी से बात कर रही हूँ?";
        hearAll();
        say("हाँ, मुझे प्याज़ का भाव बताइए");
        hearAll();

        assertThat(spoken()).endsWith("ठीक है, हिंदी में बात करते हैं।", "क्या मैं Suresh जी से बात कर रही हूँ?");
        assertThat(brain.responds).as("the farmer had not heard the name check yet").isEmpty();

        say("हाँ");
        hearAll();
        assertThat(brain.responds).singleElement().satisfies(r -> {
            assertThat(r.latest().text()).isEqualTo("हाँ");
            assertThat(r.history()).extracting(com.example.g_one.agent.AgentBrain.Line::text)
                    .contains("हाँ, मुझे प्याज़ का भाव बताइए", "क्या मैं Suresh जी से बात कर रही हूँ?");
        });
    }

    @Test
    void modelThinkingOnTheLanguagePathIsNeverSpokenShownOrKeptInTheHistory() {
        brain.greetText = "<think>greet them warmly</think>नमस्ते, जी-वन में आपका स्वागत है।";
        brain.replyText = "<think>price question</think>प्याज़ का भाव बताती हूँ।";
        hearAll();
        say("मुझे प्याज़ का भाव बताइए");
        hearAll();
        say("और टमाटर का");
        hearAll();

        assertThat(spoken()).noneMatch(ReasoningText::hasTag).noneMatch(t -> t.contains("price question"))
                .contains("ठीक है, हिंदी में बात करते हैं।", "नमस्ते, जी-वन में आपका स्वागत है।", "प्याज़ का भाव बताती हूँ।");
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class)).extracting(ConsoleEvent.AssistantSaid::text)
                .noneMatch(ReasoningText::hasTag).contains("नमस्ते, जी-वन में आपका स्वागत है।");
        assertThat(brain.responds).hasSize(2).allSatisfy(r ->
                assertThat(r.history()).extracting(com.example.g_one.agent.AgentBrain.Line::text).noneMatch(ReasoningText::hasTag));
        assertThat(brain.responds.get(1).history()).extracting(com.example.g_one.agent.AgentBrain.Line::text)
                .contains("नमस्ते, जी-वन में आपका स्वागत है।", "प्याज़ का भाव बताती हूँ।");
    }

    @Test
    void speakingOverTheLanguageQuestionStopsItAndTheTurnIsHeard() {
        assertThat(leg.unheardFrames()).as("the language question is playing").isPositive();

        stt.last().emitSpeechStart();

        assertThat(leg.clearCount()).isEqualTo(1);
        assertThat(leg.unheardFrames()).isZero();
        say("हिंदी");
        hearAll();
        assertThat(spoken()).containsExactly(spoken().get(0), "ठीक है, हिंदी में बात करते हैं।", ScriptedBrain.GREETING);
    }

    @Test
    void speakingOverTheConfirmationStopsItAfterTheRecognitionMovedLanguage() {
        hearAll();
        say("हिंदी");
        assertThat(stt.last().retargets()).extracting(Language::code).containsExactly("hi-IN");
        assertThat(leg.unheardFrames()).as("the confirmation is playing").isPositive();

        stt.last().emitSpeechStart();

        assertThat(leg.clearCount()).isEqualTo(1);
        assertThat(leg.unheardFrames()).isZero();
    }

    @Test
    void speakingOverTheConfirmationDropsTheQueuedAnswer() {
        hearAll();
        say("मुझे प्याज़ का भाव बताइए");
        say("अभी रुकिए");
        hearAll();
        assertThat(brain.responds).extracting(r -> r.latest().text()).containsExactly("अभी रुकिए");
    }

    @Test
    void twoUnclearAnswersAskOnceMoreThenSettleOnHinglish() {
        hearAll();
        say("haan");
        assertThat(spoken().get(1)).startsWith("Sorry, I did not catch that.").contains("कृपया");
        assertThat(tts.requests().get(1).language().code()).isEqualTo("hi-en");
        assertThat(leg.languagePending()).isTrue();
        hearAll();
        say("1500");
        hearAll();
        assertThat(leg.language().code()).isEqualTo("hi-en");
        assertThat(eventsOf(ConsoleEvent.LanguageChanged.class)).singleElement().satisfies(e -> assertThat(e.reason()).isEqualTo("default"));
        assertThat(brain.greets).hasSize(1);
    }

    @Test
    void aSwitchMidCallMovesTheLegAndItsRecognitionWithoutSpeaking() {
        hearAll();
        say("Hindi");
        hearAll();
        int before = spoken().size();

        say("ನನಗೆ ಈರುಳ್ಳಿ ಬೆಲೆ ಬೇಕು");
        hearAll();
        assertThat(leg.language().code()).as("one turn is not a switch").isEqualTo("hi-IN");
        say("ಈರುಳ್ಳಿ ಬೆಲೆ ಎಷ್ಟು ಹೇಳಿ");
        hearAll();

        assertThat(leg.language().code()).isEqualTo("kn-IN");
        assertThat(stt.last().retargets()).extracting(Language::code).containsExactly("hi-IN", "kn-IN");
        assertThat(spoken().subList(before, spoken().size())).as("only replies, no confirmation")
                .containsExactly(ScriptedBrain.replyTo("ನನಗೆ ಈರುಳ್ಳಿ ಬೆಲೆ ಬೇಕು"), ScriptedBrain.replyTo("ಈರುಳ್ಳಿ ಬೆಲೆ ಎಷ್ಟು ಹೇಳಿ"));
        ScriptedBrain.RespondCall last = brain.responds.get(brain.responds.size() - 1);
        assertThat(last.ctx().language().code()).isEqualTo("kn-IN");
        assertThat(last.latest().language().code()).isEqualTo("kn-IN");
    }

    @Test
    void aReplyInAnotherLanguageDoesNotMoveAFollowedLeg() {
        hearAll();
        say("Hindi");
        hearAll();
        brain.replyIn = FakeLeg.ENGLISH;
        say("मुझे प्याज़ का भाव बताइए");
        hearAll();
        say("अब गेहूँ का भाव बताइए");
        hearAll();
        assertThat(brain.responds.get(brain.responds.size() - 1).ctx().language().code()).isEqualTo("hi-IN");
    }

    @Test
    void aLegStartedWithALanguageNeverDetects() {
        FakeLeg fixed = FakeLeg.farmer(CallId.random());
        registry.register(fixed);
        executor.runAll();
        assertThat(stt.last().language()).isEqualTo(FakeLeg.HINDI);
        stt.last().say("ನನಗೆ ಈರುಳ್ಳಿ ಬೆಲೆ ಬೇಕು");
        stt.last().say("ಈರುಳ್ಳಿ ಬೆಲೆ ಎಷ್ಟು ಹೇಳಿ");
        executor.runAll();
        assertThat(fixed.language()).isEqualTo(FakeLeg.HINDI);
        assertThat(stt.last().retargets()).isEmpty();
        assertThat(eventsOf(ConsoleEvent.LanguageChanged.class)).isEmpty();
    }
}
