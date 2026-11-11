package com.example.g_one.agent.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.GOneProperties;
import com.example.g_one.agent.closing.CallClosing;
import com.example.g_one.agent.closing.ClosingLines;
import com.example.g_one.agent.llm.ChatClient;
import com.example.g_one.agent.mediation.MediationEngine;
import com.example.g_one.agent.mediation.MediationLines;
import com.example.g_one.agent.mediation.TurnReader;
import com.example.g_one.agent.pipeline.CallEndHandler.EndedCall;
import com.example.g_one.agent.tools.buyers.Buyer;
import com.example.g_one.agent.tools.buyers.BuyerConsent;
import com.example.g_one.agent.tools.mandi.MandiCatalog;
import com.example.g_one.agent.tools.mandi.MandiQuote;
import com.example.g_one.agent.tools.mandi.MandiSource;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.core.CallGist;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.ConsoleEvent.DealState;
import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegRegistry;
import com.example.g_one.core.LegRole;
import com.example.g_one.fakes.FakeLeg;
import com.example.g_one.fakes.FakeLlm;
import com.example.g_one.fakes.FakeSpeechToText;
import com.example.g_one.fakes.FakeTextToSpeech;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A party speaking to G-One directly during mediation, through the real pipeline with the fakes and a fake model server.
 * The asker alone hears the answer; the other party hears nothing of the question; the buyer's model request never holds
 * anything private to the farmer. Nothing leaves the machine.
 */
class AskGoneTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    /** Planted in the farmer's memory: it must never reach the buyer or the buyer's model request. */
    private static final String GIST_SECRET = "SECRET-GIST farmer will not go below 3900 rupees";
    private static final String NO_CAUTION = "{\"status\":\"none\",\"secretRequest\":false,\"secretShare\":false,\"advancePayment\":false}";
    private static final String NO_OFFER = "{\"status\":\"none\"}";

    private final AtomicLong now = new AtomicLong(1_762_600_000_000L);
    private final ManualExecutor executor = new ManualExecutor();
    private final ScriptedBrain brain = new ScriptedBrain();
    private final FakeSpeechToText stt = new FakeSpeechToText(now::get);
    private final FakeTextToSpeech tts = new FakeTextToSpeech();
    private final InMemoryMemoryStore store = new InMemoryMemoryStore();
    private final ConsoleBus bus = new ConsoleBus();
    private final List<ConsoleEvent> events = new CopyOnWriteArrayList<>();
    private final List<EndedCall> ended = new CopyOnWriteArrayList<>();
    private final CallId call = CallId.random();
    private final BuyerConsent consent = new BuyerConsent();
    private FakeLlm llm;
    private LegRegistry registry;
    private FakeLeg farmer;
    private FakeLeg buyer;

    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        llm = new FakeLlm();
        bus.subscribe(events::add);
        GOneProperties props = new GOneProperties(List.of("hi-IN:Hindi:Nalini", "en-IN:English:Kaveri", "hi-en:Hinglish:Poorvi"), "",
                new GOneProperties.Agmarknet("http://127.0.0.1:9", "", "", "", "", "", "", 5, 3, 30), null,
                new GOneProperties.Llm(llm.baseUrl(), FakeLlm.KEY, "fake-model", 5));
        MandiCatalog catalog = new MandiCatalog(JSON);
        Language.Registry languages = new Language.Registry(props);
        MediationEngine engine = new MediationEngine(new TurnReader(new ChatClient(props, JSON), JSON),
                new MediationLines(JSON), new MandiQuote(new MandiSource(props, JSON), catalog), catalog, consent,
                new MemoryGate(), bus, languages, clock);
        store.saveGist(FakeLeg.FARMER_KEY, new CallGist(CallId.random(), 1L, 2L, GIST_SECRET, List.of()));
        registry = new LegRegistry();
        new CallPipeline(brain, stt, tts, store, bus, List.of(ended::add), executor, now::get, engine).attach(registry);
        farmer = FakeLeg.farmer(call);
        registry.register(farmer);
        executor.runAll();
        farmer.finishPlayback();
    }

    @AfterEach
    void tearDown() {
        llm.close();
    }

    // ---- helpers ----

    private void buyerJoinsAndAgrees(String quantity) {
        consent.suggested(call, List.of("mehta-agro"), "Onion", quantity, "Nashik");
        consent.requested(call, new Buyer("mehta-agro", "Mehta Agro Traders", "trader", "Pune", List.of(), "en-IN", 92,
                "MEHTA_AGRO", List.of()));
        buyer = FakeLeg.buyer(call);
        registry.register(buyer);
        executor.runAll();
        buyer.finishPlayback();
        session(LegRole.BUYER).say("Yes");
        executor.runAll();
        finishAll();
    }

    private void buyerJoinsAndAgrees() {
        buyerJoinsAndAgrees("20 quintal");
    }

    private FakeSpeechToText.Session session(LegRole role) {
        return stt.sessions().stream().filter(s -> s.role() == role).reduce((a, b) -> b).orElseThrow();
    }

    private void finishAll() {
        for (int i = 0; i < 6; i++) {
            farmer.finishPlayback();
            buyer.finishPlayback();
            executor.runAll();
        }
    }

    private static String reading(String translation, String offerJson, String reply, boolean toAssistant) {
        return "{\"translation\":\"" + translation + "\",\"offer\":" + offerJson + ",\"caution\":" + NO_CAUTION
                + ",\"reply\":\"" + reply + "\",\"toAssistant\":" + toAssistant + "}";
    }

    private static String clearOffer(int price) {
        return "{\"status\":\"clear\",\"commodity\":\"Onion\",\"variety\":\"Red\",\"market\":null,\"price\":" + price
                + ",\"currency\":\"INR\",\"unit\":\"quintal\",\"pickup\":\"Monday at the farm\",\"payment\":\"on delivery\"}";
    }

    private static String answer(String text) {
        return "{\"answer\":\"" + text + "\"}";
    }

    private List<String> spokenTo(FakeLeg leg) {
        // Farmer lines are Hindi and buyer lines English, so the language tells the requests apart.
        return tts.requests().stream().filter(r -> r.language().equals(leg.language())).map(FakeTextToSpeech.Request::text).toList();
    }

    private <T extends ConsoleEvent> List<T> eventsOf(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private void buyerOffers(int price) {
        llm.replyText(reading("चार हज़ार रुपये प्रति क्विंटल", clearOffer(price), "none", false));
        session(LegRole.BUYER).say("I can pay " + price + " per quintal");
        executor.runAll();
        finishAll();
    }

    private void farmerSaid(String text, String translation) {
        llm.replyText(reading(translation, NO_OFFER, "none", false));
        session(LegRole.FARMER).say(text);
        executor.runAll();
        finishAll();
    }

    /** The user message of a model request: the JSON object the model reads. */
    private static JsonNode input(JsonNode request) throws Exception {
        return JSON.readTree(request.path("messages").get(1).path("content").asText());
    }

    private static String system(JsonNode request) {
        return request.path("messages").get(0).path("content").asText();
    }

    private List<ConsoleEvent.AssistantSaid> saidTo(LegRole role) {
        return eventsOf(ConsoleEvent.AssistantSaid.class).stream().filter(a -> a.role() == role).toList();
    }

    // ---- both directions: answered on the asker's leg only, never relayed ----

    @Test
    void aBuyerAskingByNameIsAnsweredOnTheirLegInOneModelCallAndTheFarmerHearsNothing() throws Exception {
        buyerJoinsAndAgrees();
        farmerSaid("bees quintal laal pyaaz hai", "I have twenty quintals of red onion");
        int requests = llm.requests().size();
        int translated = eventsOf(ConsoleEvent.TurnTranslated.class).size();
        List<String> farmerHeard = spokenTo(farmer);
        int farmerFrames = farmer.played().size();
        int farmerClears = farmer.clearCount();
        llm.replyText(answer("The farmer said twenty quintals of red onion."));

        session(LegRole.BUYER).say("G-One, how much crop is that farmer trying to sell?");
        executor.runAll();
        finishAll();

        assertThat(llm.requests()).as("one model call for an addressed turn").hasSize(requests + 1);
        JsonNode request = llm.requests().get(requests);
        assertThat(system(request)).contains("The asker is the buyer");
        assertThat(input(request).path("question").asText()).contains("how much crop");
        assertThat(input(request).path("call").path("deal").path("quantity").asText()).isEqualTo("20 quintal");
        assertThat(eventsOf(ConsoleEvent.TurnTranslated.class)).as("never relayed").hasSize(translated);
        assertThat(spokenTo(farmer)).as("the farmer hears nothing of it").isEqualTo(farmerHeard);
        assertThat(farmer.played()).hasSize(farmerFrames);
        assertThat(farmer.clearCount()).isEqualTo(farmerClears);
        assertThat(spokenTo(buyer)).last().isEqualTo("The farmer said twenty quintals of red onion.");
        assertThat(saidTo(LegRole.BUYER)).last().satisfies(a -> {
            assertThat(a.legId()).isEqualTo(buyer.id());
            assertThat(a.text()).isEqualTo("The farmer said twenty quintals of red onion.");
        });
        assertThat(eventsOf(ConsoleEvent.TurnHeard.class)).anySatisfy(t -> {
            assertThat(t.role()).isEqualTo(LegRole.BUYER);
            assertThat(t.text()).contains("G-One, how much crop");
        });
    }

    @Test
    void aFarmerAskingInHindiHearsTheBuyersOfferAndTheMarketReferenceTheBuyerHearsNothing() throws Exception {
        buyerJoinsAndAgrees();
        buyerOffers(4000);
        int requests = llm.requests().size();
        List<String> buyerHeard = spokenTo(buyer);
        int buyerClears = buyer.clearCount();
        int translated = eventsOf(ConsoleEvent.TurnTranslated.class).size();
        String reply = "ख़रीदार ने चार हज़ार रुपये प्रति क्विंटल कहा है।";
        llm.replyText(answer(reply));

        session(LegRole.FARMER).say("जी-वन, खरीदार ने क्या भाव बोला?");
        executor.runAll();
        finishAll();

        assertThat(llm.requests()).hasSize(requests + 1);
        JsonNode request = llm.requests().get(requests);
        assertThat(system(request)).contains("The asker is the farmer");
        JsonNode view = input(request).path("call");
        assertThat(view.path("deal").path("priceRupees").toString()).as("plain, never 4E+3").isEqualTo("4000");
        assertThat(view.path("deal").path("pricePerKgRupees").toString()).isEqualTo("40");
        assertThat(view.path("deal").path("estimatedValueRupees").asInt()).isEqualTo(80000);
        assertThat(view.path("marketReference").path("source").asText()).contains("AGMARKNET");
        assertThat(view.path("otherParty").path("name").asText()).isEqualTo("Mehta Agro Traders");
        assertThat(view.toString()).doesNotContain("MEHTA_AGRO").doesNotContain("+1500");
        assertThat(eventsOf(ConsoleEvent.TurnTranslated.class)).hasSize(translated);
        assertThat(spokenTo(buyer)).isEqualTo(buyerHeard);
        assertThat(buyer.clearCount()).isEqualTo(buyerClears);
        assertThat(spokenTo(farmer)).last().isEqualTo(reply);
        assertThat(saidTo(LegRole.FARMER)).last().satisfies(a -> assertThat(a.legId()).isEqualTo(farmer.id()));
    }

    @Test
    void aTurnTheModelReadsAsSpokenToTheAssistantIsAnsweredInTwoCallsAndNeverBecomesADealTerm() throws Exception {
        buyerJoinsAndAgrees();
        int requests = llm.requests().size();
        int translated = eventsOf(ConsoleEvent.TurnTranslated.class).size();
        List<String> farmerHeard = spokenTo(farmer);
        // The model put a quantity in the offer too: it must not reach the deal.
        llm.replyText(reading("क्या आप बता सकते हैं कि उनके पास पचास क्विंटल है?",
                "{\"status\":\"none\",\"quantity\":\"50 quintal\"}", "none", true));
        llm.replyText(answer("So far twenty quintals have been mentioned."));

        session(LegRole.BUYER).say("Can you tell me if he has fifty quintal?");
        executor.runAll();
        finishAll();

        assertThat(llm.requests()).as("the turn read, then the answer").hasSize(requests + 2);
        assertThat(system(llm.requests().get(requests))).contains("toAssistant");
        assertThat(system(llm.requests().get(requests + 1))).contains("The asker is the buyer");
        assertThat(eventsOf(ConsoleEvent.TurnTranslated.class)).hasSize(translated);
        assertThat(spokenTo(farmer)).isEqualTo(farmerHeard);
        assertThat(spokenTo(buyer)).last().isEqualTo("So far twenty quintals have been mentioned.");

        // The next question's view still has the quantity the buyer heard, not the one in the question.
        llm.replyText(answer("Twenty quintals."));
        session(LegRole.BUYER).say("G-One, and the quantity?");
        executor.runAll();
        finishAll();
        JsonNode view = input(llm.requests().get(llm.requests().size() - 1)).path("call");
        assertThat(view.path("deal").path("quantity").asText()).isEqualTo("20 quintal");
        assertThat(view.path("lines").toString()).as("the follow-up sees the earlier question and answer")
                .contains("you, to G-One only").contains("So far twenty quintals");
    }

    @Test
    void anOrdinaryTurnStillCostsOneModelCallAndIsTranslated() {
        buyerJoinsAndAgrees();
        int requests = llm.requests().size();
        llm.replyText(reading("मैं कल माल उठा लूँगा", NO_OFFER, "none", false));

        session(LegRole.BUYER).say("I will pick it up tomorrow");
        executor.runAll();
        finishAll();

        assertThat(llm.requests()).as("one model call for an ordinary relayed turn").hasSize(requests + 1);
        assertThat(spokenTo(farmer)).last().isEqualTo("मैं कल माल उठा लूँगा");
        assertThat(saidTo(LegRole.BUYER)).noneMatch(a -> a.atMs() > 0 && a.text().contains("उठा"));
    }

    @Test
    void aThirdPersonMentionOfTheNameIsRelayedNotAnswered() {
        buyerJoinsAndAgrees();
        int requests = llm.requests().size();
        llm.replyText(reading("जी-वन ने कहा था कि ठीक है", NO_OFFER, "none", false));

        session(LegRole.BUYER).say("The G-One said it is fine");
        executor.runAll();
        finishAll();

        assertThat(llm.requests()).hasSize(requests + 1);
        assertThat(spokenTo(farmer)).last().isEqualTo("जी-वन ने कहा था कि ठीक है");
    }

    // ---- honesty: unknown figures, failed model, invented numbers ----

    @Test
    void anUnstatedQuantityIsSaidPlainlyWhenTheModelFails() throws Exception {
        buyerJoinsAndAgrees(null);
        llm.failures.set(1);

        session(LegRole.BUYER).say("G-One, how much does the farmer have?");
        executor.runAll();
        finishAll();

        assertThat(spokenTo(buyer)).last()
                .isEqualTo("No quantity has been said on this call yet. No price has been offered on this call yet.");
        JsonNode view = input(llm.requests().get(llm.requests().size() - 1)).path("call");
        assertThat(view.path("deal").has("quantity")).isFalse();
        assertThat(view.path("notYetAgreed").toString()).contains("quantity").contains("price");
    }

    @Test
    void aFailedModelFallsBackToTheDealCardInTheAskersLanguage() {
        buyerJoinsAndAgrees();
        buyerOffers(4000);
        llm.failures.set(1);

        session(LegRole.FARMER).say("जी-वन, अब तक क्या तय हुआ?");
        executor.runAll();
        finishAll();

        assertThat(spokenTo(farmer)).last().isEqualTo(
                "इस कॉल पर अब तक मात्रा बीस क्विंटल बताई गई है। अब तक चार हज़ार रुपये प्रति क्विंटल का भाव बोला गया है।");
        assertThat(spokenTo(farmer)).last().asString().doesNotContainPattern("\\d");
    }

    @Test
    void anAnswerWithANumberNotSaidOnTheCallIsNotSpoken() {
        buyerJoinsAndAgrees();
        llm.replyText(answer("He has fifty quintals."));

        session(LegRole.BUYER).say("G-One, how much does he have?");
        executor.runAll();
        finishAll();

        assertThat(spokenTo(buyer)).noneMatch(t -> t.contains("fifty"));
        assertThat(spokenTo(buyer)).last().asString().startsWith("The quantity said on this call so far is twenty quintal.");
    }

    @Test
    void anAnswerWithAPhoneLikeNumberIsNotSpoken() {
        buyerJoinsAndAgrees();
        llm.replyText(answer("Call him on 9876543210."));

        session(LegRole.BUYER).say("G-One, what is his number?");
        executor.runAll();
        finishAll();

        assertThat(tts.requests()).noneMatch(r -> r.text().contains("9876") || r.text().contains("nine eight seven"));
    }

    // ---- privacy: the buyer never learns what was not said to them ----

    @Test
    void theBuyersViewNeverHoldsAWhisperAMemoryGistAFarmerAsideOrAWithheldSecret() throws Exception {
        buyerJoinsAndAgrees();
        buyerOffers(4000); // earns the farmer a private market-reference whisper
        String whisper = eventsOf(ConsoleEvent.WhisperSent.class).get(0).text();
        // The farmer tells G-One their floor, privately, then says a secret that is withheld.
        llm.replyText(answer("ठीक है, मैं यह किसी को नहीं बताऊँगी।"));
        session(LegRole.FARMER).say("जी-वन, मेरा कम से कम भाव तीन हज़ार नौ सौ है, यह मत बताना");
        executor.runAll();
        finishAll();
        session(LegRole.FARMER).say("mera OTP 482913 hai");
        executor.runAll();
        finishAll();
        int requests = llm.requests().size();
        llm.replyText(answer("The buyer offered four thousand rupees per quintal so far."));

        session(LegRole.BUYER).say("G-One, summarise everything so far");
        executor.runAll();
        finishAll();

        assertThat(llm.requests()).hasSize(requests + 1);
        String body = llm.requests().get(requests).toString();
        assertThat(body).doesNotContain(whisper).doesNotContain("AGMARKNET").doesNotContain("marketReference")
                .doesNotContain("SECRET-GIST").doesNotContain("3900").doesNotContain("तीन हज़ार नौ सौ")
                .doesNotContain("482913").doesNotContain("OTP").doesNotContain("यह मत बताना")
                .doesNotContain("MEHTA_AGRO").doesNotContain("Pune").doesNotContain("Nashik")
                .doesNotContain(FakeLeg.FARMER_KEY.toString()).doesNotContain("15005550006");
        assertThat(spokenTo(buyer)).noneMatch(t -> t.contains("3900") || t.contains("three thousand nine hundred"));
    }

    @Test
    void aBuyerAskingForTheFarmersLowestPriceIsRefusedWithoutTheModel() {
        buyerJoinsAndAgrees();
        int requests = llm.requests().size();
        List<String> farmerHeard = spokenTo(farmer);

        session(LegRole.BUYER).say("G-One, what is the lowest he will accept?");
        executor.runAll();
        finishAll();

        assertThat(llm.requests()).as("no model call").hasSize(requests);
        assertThat(spokenTo(buyer)).last().isEqualTo(
                "Sorry, I do not share either side's limits or private details. If you like, propose a price and I will pass it on.");
        assertThat(spokenTo(farmer)).isEqualTo(farmerHeard);
    }

    @Test
    void theFarmerMayHearTheirOwnPrivateNotesButNeverThePhoneOfTheBuyer() throws Exception {
        buyerJoinsAndAgrees();
        buyerOffers(4000);
        String whisper = eventsOf(ConsoleEvent.WhisperSent.class).get(0).text();
        llm.replyText(answer("ठीक है।"));

        session(LegRole.FARMER).say("जी-वन, मंडी का भाव क्या है?");
        executor.runAll();
        finishAll();

        String body = llm.requests().get(llm.requests().size() - 1).toString();
        assertThat(body).contains(whisper).doesNotContain("MEHTA_AGRO").doesNotContain("15005550001");
    }

    // ---- playback: the asker's own leg, behind what it is hearing; barge-in on that leg only ----

    @Test
    void theAnswerWaitsForTheAskersCurrentLineAndNeverTouchesTheOtherLeg() {
        buyerJoinsAndAgrees();
        // A farmer turn is being translated to the buyer and is still playing on the buyer's leg.
        llm.replyText(reading("I have twenty quintals", NO_OFFER, "none", false));
        session(LegRole.FARMER).say("bees quintal hai");
        executor.runAll();
        assertThat(buyer.unheardFrames()).isGreaterThan(0);
        int farmerClears = farmer.clearCount();
        int buyerClears = buyer.clearCount();
        int farmerFrames = farmer.played().size();
        llm.replyText(answer("He said he has twenty quintals."));

        // No speech start: the turn arrives while the buyer still hears the translation.
        session(LegRole.BUYER).emitTurn("G-One, what did he just say?");
        executor.runAll();

        assertThat(spokenTo(buyer)).as("queued, not yet spoken").doesNotContain("He said he has twenty quintals.");
        assertThat(buyer.clearCount()).isEqualTo(buyerClears);
        buyer.finishPlayback();
        executor.runAll();
        assertThat(spokenTo(buyer)).last().isEqualTo("He said he has twenty quintals.");
        assertThat(farmer.clearCount()).isEqualTo(farmerClears);
        assertThat(farmer.played()).hasSize(farmerFrames);
    }

    @Test
    void theAskerCanInterruptTheAnswerAndTheOtherLegIsUnaffected() {
        buyerJoinsAndAgrees();
        llm.replyText(answer("Twenty quintals of onion have been mentioned so far on this call."));
        session(LegRole.BUYER).say("G-One, how much onion?");
        executor.runAll();
        assertThat(buyer.unheardFrames()).as("the answer is playing").isGreaterThan(0);
        int farmerClears = farmer.clearCount();
        int buyerClears = buyer.clearCount();

        session(LegRole.BUYER).emitSpeechStart();

        assertThat(buyer.clearCount()).isEqualTo(buyerClears + 1);
        assertThat(farmer.clearCount()).isEqualTo(farmerClears);
    }

    // ---- flow: confirmation, the end-of-call check, held turns ----

    @Test
    void aYesToTheReadBackThatNamesGOneIsAConfirmationNotAQuestion() {
        buyerJoinsAndAgrees();
        buyerOffers(4000);
        llm.replyText(reading("Yes, agreed", NO_OFFER, "yes", false));
        session(LegRole.FARMER).say("haan theek hai");
        executor.runAll();
        finishAll();
        int requests = llm.requests().size();

        session(LegRole.BUYER).say("G-One, yes");
        executor.runAll();
        finishAll();

        assertThat(llm.requests()).as("a plain yes needs no model").hasSize(requests);
        List<ConsoleEvent.DealUpdated> deals = eventsOf(ConsoleEvent.DealUpdated.class);
        assertThat(deals.get(deals.size() - 1).buyerYes()).as("counted as the buyer's yes to the read-back").isTrue();
        assertThat(deals.get(deals.size() - 1).state()).isEqualTo(DealState.CONFIRMING);
    }

    @Test
    void whileTheEndOfCallCheckIsAskedAQuestionToGOneIsReadAsItsAnswer() {
        buyerJoinsAndAgrees();
        session(LegRole.FARMER).say("bye");
        executor.runAll();
        int requests = llm.requests().size();
        int buyerAnswers = saidTo(LegRole.BUYER).size();

        session(LegRole.BUYER).say("G-One, how much does he have?");
        executor.runAll();
        finishAll();

        assertThat(llm.requests()).as("the end check reads words, not the model").hasSize(requests);
        assertThat(saidTo(LegRole.BUYER).subList(buyerAnswers, saidTo(LegRole.BUYER).size()))
                .noneMatch(a -> a.text().startsWith("The quantity said") || a.text().startsWith("No quantity"));
        assertThat(spokenTo(buyer)).contains(ClosingLines.ask(CallClosing.Question.END, FakeLeg.ENGLISH.code()));
    }

    @Test
    void aFarmerQuestionRacingTheBuyersYesIsAnsweredInMediationNotByTheIntakeAssistant() {
        consent.suggested(call, List.of("mehta-agro"), "Onion", "20 quintal", "Nashik");
        consent.requested(call, new Buyer("mehta-agro", "Mehta Agro Traders", "trader", "Pune", List.of(), "en-IN", 92,
                "MEHTA_AGRO", List.of()));
        buyer = FakeLeg.buyer(call);
        registry.register(buyer);
        executor.runAll();
        buyer.finishPlayback();
        session(LegRole.BUYER).say("Yes"); // queued on the mediation lane, not yet run
        brain.onIdentityCheck.set(executor::runAll);
        llm.replyText(answer("अभी तक बीस क्विंटल की बात हुई है।"));

        session(LegRole.FARMER).emitTurn("जी-वन, कितना माल बताया है?");
        executor.runAll();
        finishAll();

        assertThat(brain.responds).as("never answered by the intake assistant").isEmpty();
        assertThat(eventsOf(ConsoleEvent.TurnTranslated.class)).as("never relayed").isEmpty();
        assertThat(spokenTo(farmer)).contains("अभी तक बीस क्विंटल की बात हुई है।");
        assertThat(spokenTo(buyer)).noneMatch(t -> t.contains("बीस"));
    }

    @Test
    void theQuestionAndAnswerReachTheTranscriptButTheOtherPartysNextTurnIsStillTranslated() {
        buyerJoinsAndAgrees();
        llm.replyText(answer("Twenty quintals so far."));
        session(LegRole.BUYER).say("G-One, how much?");
        executor.runAll();
        finishAll();

        farmerSaid("haan bees quintal", "Yes, twenty quintals");

        assertThat(spokenTo(buyer)).last().isEqualTo("Yes, twenty quintals");
        farmer.remoteHangup();
        executor.runAll();
        assertThat(ended).singleElement().satisfies(e -> assertThat(e.transcript()).extracting(line -> line.text())
                .contains("Twenty quintals so far."));
    }
}
