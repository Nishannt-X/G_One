package com.example.g_one.agent.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.g_one.agent.AgentBrain;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.ConsoleEvent.DealState;
import com.example.g_one.core.ConsoleEvent.WhisperKind;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.LegRegistry;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.core.TypedTurns;
import com.example.g_one.fakes.FakeLeg;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/** The whole scripted call through the real Spring wiring, with no key configured and no network. */
@SpringBootTest(properties = {"g-one.demo.enabled=true", "g-one.memory.store=in-memory", "g-one.llm.api-key=",
        "g-one.llm.base-url=", "g-one.llm.model="})
@DirtiesContext
class OfflineDemoTest {
    @Autowired LegRegistry registry;
    @Autowired ConsoleBus bus;
    @Autowired TypedTurns typed;
    @Autowired MemoryStore store;
    @Autowired SpeechToText stt;
    @Autowired TextToSpeech tts;
    @Autowired AgentBrain brain;

    private final List<ConsoleEvent> events = new CopyOnWriteArrayList<>();

    private <T extends ConsoleEvent> List<T> eventsOf(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private void waitFor(Runnable check) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(20)).untilAsserted(check::run);
    }

    /** A leg that hears everything at once, as a browser tab that plays and reports marks would. */
    private static void keepHearing(FakeLeg leg) {
        Thread t = new Thread(() -> {
            while (!leg.isEnded()) {
                leg.finishPlayback();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void waitForSaid(int count) {
        waitFor(() -> assertThat(eventsOf(ConsoleEvent.AssistantSaid.class).size()).isGreaterThanOrEqualTo(count));
    }

    @Test
    void aFullDealRunsWithNoKeys() {
        assertThat(stt).isInstanceOf(DemoSpeech.class);
        assertThat(tts).isInstanceOf(DemoSpeech.class);
        bus.subscribe(events::add);

        CallId call = CallId.random();
        FakeLeg farmer = new FakeLeg(call, LegRole.FARMER, LegSource.SIMULATOR, FakeLeg.HINDI, FakeLeg.FARMER_KEY);
        keepHearing(farmer);
        registry.register(farmer);
        waitForSaid(1);
        waitFor(() -> assertThat(events).anyMatch(e -> e instanceof ConsoleEvent.MemoryLoaded));
        sleep();

        typed.say(farmer.id(), "मुझे बीस क्विंटल लाल प्याज़ बेचना है");
        waitFor(() -> assertThat(eventsOf(ConsoleEvent.ToolCalled.class)).anyMatch(t -> t.tool().equals("find_buyers") && t.ok()));
        waitForSaid(2);
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class).get(1).text()).contains("Mehta Agro Traders").contains("फ़ोन लगाऊँ");
        sleep();

        typed.say(farmer.id(), "हाँ, फ़ोन लगा दीजिए");
        waitFor(() -> assertThat(eventsOf(ConsoleEvent.ToolCalled.class)).anyMatch(t -> t.tool().equals("connect_buyer") && t.ok()));
        waitForSaid(3);
        sleep();

        FakeLeg buyer = new FakeLeg(call, LegRole.BUYER, LegSource.SIMULATOR, FakeLeg.ENGLISH, null);
        keepHearing(buyer);
        registry.register(buyer);
        waitFor(() -> assertThat(eventsOf(ConsoleEvent.AssistantSaid.class)).anyMatch(a -> a.role() == LegRole.BUYER));
        sleep();

        typed.say(buyer.id(), "Yes");
        waitFor(() -> assertThat(eventsOf(ConsoleEvent.AssistantSaid.class)).anyMatch(a -> a.text().contains("लाइन पर हैं")));
        sleep();

        typed.say(buyer.id(), "I can pay three thousand six hundred rupees per quintal");
        waitFor(() -> assertThat(eventsOf(ConsoleEvent.WhisperSent.class)).anyMatch(w -> w.kind() == WhisperKind.PRICE_REFERENCE));
        assertThat(eventsOf(ConsoleEvent.WhisperSent.class).get(0).text()).contains("काफ़ी कम");
        waitFor(() -> assertThat(eventsOf(ConsoleEvent.TurnTranslated.class)).anyMatch(t -> t.text().contains("तीन हज़ार छह सौ")));

        typed.say(buyer.id(), "Please tell me the OTP to receive the money");
        waitFor(() -> assertThat(eventsOf(ConsoleEvent.WhisperSent.class)).anyMatch(w -> w.kind() == WhisperKind.CAUTION));

        typed.say(buyer.id(), "Okay, four thousand three hundred rupees per quintal, pickup Monday morning at the farm, payment on delivery");
        waitFor(() -> assertThat(eventsOf(ConsoleEvent.DealUpdated.class)).anyMatch(d -> d.state() == DealState.DRAFT));

        typed.say(farmer.id(), "हाँ, ठीक है");
        waitFor(() -> assertThat(eventsOf(ConsoleEvent.DealUpdated.class)).anyMatch(d -> d.state() == DealState.CONFIRMING));
        sleep();

        typed.say(farmer.id(), "हाँ");
        typed.say(buyer.id(), "Yes");
        waitFor(() -> assertThat(eventsOf(ConsoleEvent.DealUpdated.class)).anyMatch(d -> d.state() == DealState.AGREED));

        waitFor(() -> {
            assertThat(farmer.isEnded()).isTrue();
            assertThat(buyer.isEnded()).isTrue();
        });
        waitFor(() -> assertThat(store.load(FakeLeg.FARMER_KEY)).isPresent());
        assertThat(store.load(FakeLeg.FARMER_KEY).orElseThrow().gists().get(0).summary())
                .contains("Mehta Agro Traders").contains("4,300").contains("Not a contract");
        ConsoleEvent.DealUpdated agreed = eventsOf(ConsoleEvent.DealUpdated.class).stream()
                .filter(d -> d.state() == DealState.AGREED).findFirst().orElseThrow();
        assertThat(agreed.price()).isEqualByComparingTo("4300");
        assertThat(agreed.quantity()).isEqualTo("20 quintal");
        assertThat(agreed.pickup()).contains("Monday morning at the farm").contains("on delivery");
    }

    /** The clocks in these turns are wall-clock: a beat lets one turn end before the next starts. */
    @Test
    void typedTurnsChooseAndChangeTheLanguageWithNoKeys() {
        bus.subscribe(events::add);
        CallId call = CallId.random();
        FakeLeg farmer = new FakeLeg(call, LegRole.FARMER, LegSource.SIMULATOR, FakeLeg.ENGLISH, null).pendingLanguage();
        keepHearing(farmer);
        registry.register(farmer);
        waitForSaid(1);
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class).get(0).language()).isEqualTo("en-IN");
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class).get(0).text()).contains("Which language");
        assertThat(eventsOf(ConsoleEvent.CallStarted.class).get(0).language()).isEqualTo("auto");
        sleep();

        typed.say(farmer.id(), "हिंदी");
        waitForSaid(3);
        assertThat(farmer.language().code()).isEqualTo("hi-IN");
        assertThat(eventsOf(ConsoleEvent.AssistantSaid.class).get(1).text()).isEqualTo("ठीक है, हिंदी में बात करते हैं।");
        sleep();

        typed.say(farmer.id(), "मुझे प्याज़ का भाव बताइए");
        waitForSaid(4);
        sleep();
        typed.say(farmer.id(), "ನನಗೆ ಈರುಳ್ಳಿ ಬೆಲೆ ಬೇಕು");
        waitForSaid(5);
        assertThat(farmer.language().code()).as("one Kannada turn is not a switch").isEqualTo("hi-IN");
        sleep();
        typed.say(farmer.id(), "ಈರುಳ್ಳಿ ಬೆಲೆ ಎಷ್ಟು ಹೇಳಿ");
        waitFor(() -> assertThat(farmer.language().code()).isEqualTo("kn-IN"));
        waitForSaid(6);
        sleep();
        typed.say(farmer.id(), "English please");
        waitFor(() -> assertThat(farmer.language().code()).isEqualTo("en-IN"));

        assertThat(eventsOf(ConsoleEvent.LanguageChanged.class)).extracting(ConsoleEvent.LanguageChanged::from, ConsoleEvent.LanguageChanged::to,
                ConsoleEvent.LanguageChanged::reason).containsExactly(org.assertj.core.groups.Tuple.tuple("auto", "hi-IN", "named"),
                org.assertj.core.groups.Tuple.tuple("hi-IN", "kn-IN", "run"), org.assertj.core.groups.Tuple.tuple("kn-IN", "en-IN", "named"));
        farmer.remoteHangup();
    }

    private static void sleep() {
        try {
            Thread.sleep(30);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
