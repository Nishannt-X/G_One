package com.example.g_one.agent.deal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.deal.CompoundReply.Answer;
import com.example.g_one.agent.deal.CompoundReply.Parsed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A turn spoken while a yes or no question waits, read as its answer and the rest, in English, Hindi, Hinglish, Bengali,
 * Tamil and Kannada. The owner's real calls: "haan, but unka naam kya hai?", "ha, pehle batao kitna bhav hai", "yes, and
 * what is the buyer's name", "haan theek hai, lekin kab aayega".
 */
class CompoundReplyTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // A yes, then a follow-up question or request: the yes stands and the question is handled.
            "haan, but unka naam kya hai?|YES|unka naam kya hai?",
            "haan but unka naam kya hai|YES|unka naam kya hai",
            "haan ji unka naam kya hai|YES|unka naam kya hai",
            "yes, and what is the buyer's name|YES|what is the buyer's name",
            "Yes. Who is he?|YES|Who is he?",
            "haan theek hai, lekin kab aayega|YES|kab aayega",
            "हाँ, लेकिन उनका नाम क्या है?|YES|उनका नाम क्या है?",
            "हाँ ठीक है, पर वो कब आएगा?|YES|वो कब आएगा?",
            "हाँ जी, मंडी भाव भी बताइए|YES|मंडी भाव भी बताइए",
            "হ্যাঁ, কিন্তু ওনার নাম কী?|YES|ওনার নাম কী?",
            "ஆமா, ஆனா அவர் பெயர் என்ன?|YES|அவர் பெயர் என்ன?",
            "ಹೌದು, ಆದರೆ ಅವರ ಹೆಸರು ಏನು?|YES|ಅವರ ಹೆಸರು ಏನು?",
            "Yes, G-One, what quantity did we discuss?|YES|what quantity did we discuss?",
            "haan main Suresh hoon, bhav kya hai|YES|bhav kya hai",
            // A no, then a question: the no stands and the question is handled.
            "nahi, unka naam kya hai?|NO|unka naam kya hai?",
            "no, but what is the price?|NO|what is the price?",
            "नहीं, भाव क्या है?|NO|भाव क्या है?",
            "না, দাম কত?|NO|দাম কত?",
            // No answer, only a question: nothing recorded, the question is handled and the pending one asked again.
            "unka naam kya hai?|NONE|unka naam kya hai?",
            "who is the buyer?|NONE|who is the buyer?",
            // "First tell me": the answer waits, the question is handled.
            "ha, pehle batao kitna bhav hai|NONE|pehle batao kitna bhav hai",
            "yes, but first tell me the price|NONE|first tell me the price"})
    void anAnswerAndTheRest(String turn, Answer answer, String remainder) {
        Parsed p = CompoundReply.parse(turn);
        assertThat(p.answer()).as(turn).isEqualTo(answer);
        assertThat(p.remainder()).as(turn).isEqualTo(remainder);
        assertThat(p.decided()).as(turn).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "haan but only if 2000", "yes if he pays now", "theek hai lekin 1900 mein", "haan agar paisa turant mile",
            "haan par kisi ko mat batana", "yes, but only for this call", "हाँ, अगर वो आज पैसा दे", "हाँ लेकिन सिर्फ़ 20 क्विंटल",
            "হ্যাঁ, যদি আজ টাকা দেয়", "ஆமா, ஆனா 2000 ரூபாய் மட்டும்", "ಹೌದು, ಆದರೆ 2000 ಇದ್ದರೆ ಮಾತ್ರ",
            "Yes, G-One, only if the farmer agrees to 2000?", "haan... nahi nahi", "haan, pata nahi"})
    void aConditionOrDoubtIsNeverAYes(String turn) {
        Parsed p = CompoundReply.parse(turn);
        assertThat(p.answer()).as(turn).isNotEqualTo(Answer.YES);
        assertThat(p.conditional()).as(turn).isTrue();
        assertThat(CompoundReply.permitsYes(turn)).as(turn).isFalse();
    }

    @Test
    void amountsOutsideAQuestionAreTermsAndNeverConfirm() {
        assertThat(CompoundReply.parse("theek hai lekin 1900 mein").termsStated()).isTrue();
        assertThat(CompoundReply.parse("1900 mein theek hai, chalega").termsStated()).isTrue();
        assertThat(CompoundReply.confirmsDeal("1900 mein theek hai, chalega")).isFalse();
        assertThat(CompoundReply.confirmsDeal("theek hai lekin 1900 mein")).isFalse();
        assertThat(CompoundReply.confirmsDeal("haan, but who is he?")).isTrue();
        assertThat(CompoundReply.confirmsDeal("haan ji")).isTrue();
        assertThat(CompoundReply.parse("haan, kitne quintal the?").termsStated())
                .as("a question never states terms").isFalse();
    }

    @Test
    void aConditionKeepsOnlyAnIndependentQuestion() {
        Parsed p = CompoundReply.parse("haan agar paisa aaj mile, aur unka naam kya hai?");
        assertThat(p.answer()).isEqualTo(Answer.NONE);
        assertThat(p.remainder()).isEqualTo("unka naam kya hai?");
        assertThat(CompoundReply.parse("yes if he pays now").remainder()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {"haan|YES", "हाँ जी|YES", "no thanks|NO", "ঠিক আছে|YES", "ಇಲ್ಲ|NO"})
    void aPlainAnswerHasNothingElse(String turn, Answer answer) {
        Parsed p = CompoundReply.parse(turn);
        assertThat(p.answer()).isEqualTo(answer);
        assertThat(p.hasRemainder()).isFalse();
        assertThat(p.conditional()).isFalse();
    }

    @Test
    void aQuestionAboutTheQuestionIsJustAskedAgain() {
        for (String turn : new String[] {"haan?", "kya?", "kya matlab", "what?"}) {
            Parsed p = CompoundReply.parse(turn);
            assertThat(p.answer()).as(turn).isEqualTo(Answer.NONE);
            assertThat(p.hasRemainder()).as(turn).isFalse();
        }
    }

    @Test
    void onlyATurnTheWordsCannotSettleIsLeftForTheModel() {
        assertThat(CompoundReply.parse("aap yaad rakhiyega bhai").decided()).isFalse();
        assertThat(CompoundReply.parse("who is he? haan connect karo").decided()).isFalse();
        assertThat(CompoundReply.parse("haan, but unka naam kya hai?").decided()).isTrue();
        assertThat(CompoundReply.parse("pata nahi").decided()).isTrue();
    }

    // ---- review round: a clause beside the answer is a follow-up only when it is positively a question ----

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            // A negation or restriction is a qualification, even beside a request verb like "tell" or "batao".
            "yes but don't tell anyone", "yes but tell nobody", "yes, but do not share it", "yes, never tell him",
            "haan lekin kisi ko mat batana", "haan, kisi ko mat batao", "हाँ, लेकिन किसी को मत बताना",
            "হ্যাঁ, কিন্তু কাউকে বলবেন না", "ஆமா, ஆனா யாரிடமும் சொல்லாதீங்க", "ಹೌದು, ಆದರೆ ಯಾರಿಗೂ ಹೇಳಬೇಡಿ",
            "haan, bina naam ke", "yes, but only for this call", "yes, without my name"})
    void aRestrictionBesideAYesIsNeverAFollowUp(String turn) {
        Parsed p = CompoundReply.parse(turn);
        assertThat(p.answer()).as(turn).isNotEqualTo(Answer.YES);
        assertThat(p.conditional()).as(turn).isTrue();
        assertThat(CompoundReply.permitsYes(turn)).as(turn).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            // A temporal or conditional clause with an amount is a counter offer, never a question to answer privately.
            "yes when you pay 4500", "haan jab 4500 do", "yes after you pay 4500", "yes if he pays now",
            "haan par kal tak 4500", "yes when you pay 4500?", "हाँ, जब आप 4500 देंगे", "হ্যাঁ, যখন ৪৫০০ দেবেন",
            "ஆமா, 4500 கொடுக்கும் போது", "ಹೌದು, 4500 ಕೊಟ್ಟಾಗ", "yes once you pay 4500", "yes as long as it is 4500"})
    void aTermOrConditionBesideAYesNeverConfirmsADeal(String turn) {
        Parsed p = CompoundReply.parse(turn);
        assertThat(CompoundReply.confirmsDeal(turn)).as(turn).isFalse();
        assertThat(p.answer() == Answer.YES && !p.conditional() && p.hasRemainder()
                && CompoundReply.followUp(p.remainder())).as(turn + ": never a yes with a private follow-up").isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "yes when will he come?|when will he come?", "yes, when is the pickup?|when is the pickup?",
            "haan, but tell me his name|tell me his name", "haan, unka naam batao na|unka naam batao na",
            "हाँ, वो कब आएँगे?|वो कब आएँगे?", "হ্যাঁ, উনি কখন আসবেন?|উনি কখন আসবেন?",
            "ஆமா, அவர் எப்போது வருவார்?|அவர் எப்போது வருவார்?", "ಹೌದು, ಅವರು ಯಾವಾಗ ಬರುತ್ತಾರೆ?|ಅವರು ಯಾವಾಗ ಬರುತ್ತಾರೆ?"})
    void aRealQuestionBesideAYesIsStillAFollowUp(String turn, String remainder) {
        Parsed p = CompoundReply.parse(turn);
        assertThat(p.answer()).as(turn).isEqualTo(Answer.YES);
        assertThat(p.conditional()).as(turn).isFalse();
        assertThat(p.termsStated()).as(turn).isFalse();
        assertThat(p.remainder()).as(turn).isEqualTo(remainder);
        assertThat(CompoundReply.followUp(p.remainder())).as(turn).isTrue();
        assertThat(CompoundReply.confirmsDeal(turn)).as(turn).isTrue();
    }

    @Test
    void aRequestAloneIsAFollowUpAndNothingElseIsNeeded() {
        Parsed p = CompoundReply.parse("tell me his name");
        assertThat(p.answer()).isEqualTo(Answer.NONE);
        assertThat(p.remainder()).isEqualTo("tell me his name");
        assertThat(p.decided()).isTrue();
        assertThat(CompoundReply.followUp("tell me his name")).isTrue();
        assertThat(CompoundReply.followUp("don't tell anyone")).isFalse();
        assertThat(CompoundReply.followUp("when you pay 4500")).isFalse();
    }

    @Test
    void aQuestionAboutThePriceBesideAYesIsAnsweredAndStillConfirms() {
        Parsed p = CompoundReply.parse("haan, but bhav kya hai?");
        assertThat(p.answer()).isEqualTo(Answer.YES);
        assertThat(p.remainder()).isEqualTo("bhav kya hai?");
        assertThat(p.termsStated()).as("a question never states terms").isFalse();
        assertThat(CompoundReply.confirmsDeal("haan, but bhav kya hai?")).isTrue();
        assertThat(CompoundReply.parse("haan, but aaj ka bhav kya hai?").answer())
                .as("a time word fails closed, even in a question").isEqualTo(Answer.NONE);
    }

    @Test
    void nothingElseAfterAYesIsNotARestriction() {
        for (String turn : new String[] {"haan, aur kuch nahi", "yes, nothing else", "हाँ, और कुछ नहीं"}) {
            Parsed p = CompoundReply.parse(turn);
            assertThat(p.answer()).as(turn).isEqualTo(Answer.YES);
            assertThat(p.conditional()).as(turn).isFalse();
        }
    }

    // ---- round 3: default deny. The rest of the turn keeps the answer only when it is positively a question or a
    // ---- plain request; any statement, time, condition or anything unrecognised means no answer.

    /** Terra's 79-row table (round 2 review), with the intended strings for the rows its report could not print. */
    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "1|yes when will he come?|YES|false|false|when will he come?",
            "2|haan, unka naam kya hai?|YES|false|false|unka naam kya hai?",
            "3|ha, but who is he?|YES|false|false|who is he?",
            "4|yes but don't tell anyone|NONE|true|false|",
            "5|haan par kisi ko mat batana|NONE|true|false|",
            "6|yes if he pays now|NONE|true|false|",
            "7|yes only if 2000|NONE|true|true|",
            "8|yes but not today|NONE|true|false|",
            "9|हाँ पर सिर्फ़ 1900 में|NONE|true|true|",
            "10|yes provided he sends a truck|NONE|true|false|",
            "11|haan jab tak gaadi na aaye|NONE|true|false|",
            "12|yes when you pay 4500|NONE|true|true|",
            "13|haan jab 4500 do|NONE|true|true|",
            "14|yes after harvest|NONE|true|false|",
            "15|yes 4500 per quintal|NONE|true|true|",
            "16|हाँ कल तक|NONE|true|false|",
            "17|no what is his name?|NO|false|false|what is his name?",
            "18|no|NO|false|false|",
            "19|yes|YES|false|false|",
            "20|haan|YES|false|false|",
            "21|नहीं|NO|false|false|",
            "22|हाँ|YES|false|false|",
            "23|koi baat nahi|YES|false|false|",
            "24|zarur|YES|false|false|",
            "25|rakh lo|YES|false|false|",
            "26|chalega|YES|false|false|",
            "27|why not|YES|false|false|",
            "28|yes who is he?|YES|false|false|who is he?",
            "29|yes what is his name?|YES|false|false|what is his name?",
            "30|yes how much?|YES|false|false|how much?",
            "31|yes when will he arrive?|YES|false|false|when will he arrive?",
            "32|yes where is he?|YES|false|false|where is he?",
            "33|yes tell me his name|YES|false|false|tell me his name",
            "34|yes what is the price?|YES|false|false|what is the price?",
            "35|yes 4500?|NONE|true|true|",
            "36|yes but do not call|NONE|true|false|",
            "37|yes without telling him|NONE|true|false|",
            "38|yes unless he refuses|NONE|true|false|",
            "39|yes if he comes|NONE|true|false|",
            "40|yes only tomorrow|NONE|true|false|",
            "41|yes tomorrow|NONE|true|false|",
            "42|yes after payment|NONE|true|false|",
            "43|yes on delivery|NONE|true|false|",
            "44|yes provided payment arrives|NONE|true|false|",
            "45|haan lekin aaj nahi|NONE|true|false|",
            "46|हाँ लेकिन किसी को मत बताना|NONE|true|false|",
            "47|हाँ अगर वो आए|NONE|true|false|",
            "48|नहीं, उनका नाम क्या है?|NO|false|false|उनका नाम क्या है?",
            "49|हाँ, उनका नाम क्या है?|YES|false|false|उनका नाम क्या है?",
            "50|হ্যাঁ|YES|false|false|",
            "51|না|NO|false|false|",
            "52|হ্যাঁ, ওনার নাম কী?|YES|false|false|ওনার নাম কী?",
            "53|হ্যাঁ, কিন্তু কাউকে বলবেন না|NONE|true|false|",
            "54|হ্যাঁ, যদি টাকা দেয়|NONE|true|true|",
            "55|ஆமா|YES|false|false|",
            "56|இல்லை|NO|false|false|",
            "57|ஆமா, அவர் யார்?|YES|false|false|அவர் யார்?",
            "58|ஆமா, ஆனா யாரிடமும் சொல்லாதீங்க|NONE|true|false|",
            "59|ஆமா, பணம் வந்தால்|NONE|true|false|",
            "60|ಹೌದು|YES|false|false|",
            "61|ಇಲ್ಲ|NO|false|false|",
            "62|ಹೌದು, ಅವರು ಯಾರು?|YES|false|false|ಅವರು ಯಾರು?",
            "63|ಹೌದು, ಆದರೆ ಯಾರಿಗೂ ಹೇಳಬೇಡಿ|NONE|true|false|",
            "64|ಹೌದು ಹಣ ಬಂದರೆ|NONE|true|false|",
            "65|yes um well I think it is fine and please tell me who is coming?|YES|false|false|please tell me who is coming?",
            "66|yes but I think perhaps if the buyer comes tomorrow we can talk|NONE|true|false|",
            "67|haan haan ji uh unka naam kya hai|YES|false|false|unka naam kya hai",
            "68|yes uh no wait|NONE|true|false|",
            "69|yes 4 500 per quintal|NONE|true|true|",
            "70|haan jab chaar hazaar paanch sau do|NONE|true|true|",
            "71|yes but not now|NONE|true|false|",
            "72|yes as long as he pays|NONE|true|false|",
            "73|yes once he pays|NONE|true|false|",
            "74|yes tell nobody|NONE|true|false|",
            "75|yes do not tell him|NONE|true|false|",
            "76|yes but keep it private|NONE|true|false|",
            "77|yes I will think about it|NONE|true|false|",
            "78|yes maybe|NONE|true|false|",
            "79|yes actually no|NONE|true|false|"})
    void terraTable(String row, String turn, Answer answer, boolean conditional, boolean terms, String remainder) {
        expect(turn, answer, conditional, terms, remainder);
    }

    /** Adversarial rows of our own: statements after a yes in six languages, and the questions that must survive. */
    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "a1|yes bring a truck|NONE|true|false|",
            "a2|haan gaadi bhejna|NONE|true|false|",
            "a3|ಹೌದು ನಾಳೆ|NONE|true|false|",
            "a4|হ্যাঁ কাল|NONE|true|false|",
            "a5|haan kal subah|NONE|true|false|",
            "a6|haan aaj shaam ko|NONE|true|false|",
            "a7|yes on Monday|NONE|true|false|",
            "a8|haan somvar ko|NONE|true|false|",
            "a9|हाँ, पैसे मिलने के बाद|NONE|true|false|",
            "a10|हाँ, डिलीवरी पर|NONE|true|false|",
            "a11|haan payment ke baad|NONE|true|false|",
            "a12|haan delivery se pehle|NONE|true|false|",
            "a13|yes, cash only|NONE|true|false|",
            "a14|yes for the whole lot|NONE|true|false|",
            "a15|হ্যাঁ, টাকা পেলে|NONE|true|true|",
            "a16|হ্যাঁ, পরশু|NONE|true|false|",
            "a17|ஆமா, நாளைக்கு|NONE|true|false|",
            "a18|ஆமா, பணம் கொடுத்த பிறகு|NONE|true|false|",
            "a19|ಹೌದು, ಸೋಮವಾರ|NONE|true|false|",
            "a20|ಹೌದು, ಲಾರಿ ಕಳಿಸಿ|NONE|true|false|",
            "a21|yes, 50 quintal|NONE|true|true|",
            "a22|haan, do hazaar per quintal|NONE|true|true|",
            "a23|ok fine my son will come|NONE|true|false|",
            "a24|haan theek hai, mera beta aayega|NONE|true|false|",
            "a25|haan ji, wo kab tak aayenge?|NONE|true|false|",
            "a26|yes, paanch kilo?|NONE|true|true|",
            "a27|हाँ कल आना|NONE|true|false|",
            "a28|ஆமா, லாரி அனுப்புங்க|NONE|true|false|",
            "a29|হ্যাঁ, গাড়ি পাঠান|NONE|true|false|",
            "p1|haan, woh kab aayenge?|YES|false|false|woh kab aayenge?",
            "p2|haan kya bhav hai?|YES|false|false|kya bhav hai?",
            "p3|haan, kitne quintal the?|YES|false|false|kitne quintal the?",
            "p4|হ্যাঁ, দাম কত?|YES|false|false|দাম কত?",
            "p5|haan main Suresh hoon, bhav kya hai|YES|false|false|bhav kya hai",
            "p6|haan, phone laga do|YES|false|false|",
            "p7|haan ji, dhanyavaad|YES|false|false|",
            "p8|haan, aur kuch nahi|YES|false|false|",
            "p9|yes, is he coming?|YES|false|false|is he coming?",
            "p10|ஆமா, விலை என்ன?|YES|false|false|விலை என்ன?",
            "p11|ಹೌದು, ಬೆಲೆ ಎಷ್ಟು?|YES|false|false|ಬೆಲೆ ಎಷ್ಟು?"})
    void adversarialTable(String row, String turn, Answer answer, boolean conditional, boolean terms, String remainder) {
        expect(turn, answer, conditional, terms, remainder);
    }

    private static void expect(String turn, Answer answer, boolean conditional, boolean terms, String remainder) {
        Parsed p = CompoundReply.parse(turn);
        assertThat(p.answer()).as(turn + " answer").isEqualTo(answer);
        assertThat(p.conditional()).as(turn + " conditional").isEqualTo(conditional);
        assertThat(p.termsStated()).as(turn + " termsStated").isEqualTo(terms);
        assertThat(p.remainder()).as(turn + " remainder").isEqualTo(remainder == null ? "" : remainder);
        assertThat(p.decided()).as(turn + " decided").isTrue();
        if (answer != Answer.YES || conditional) {
            assertThat(CompoundReply.permitsYes(turn)).as(turn + " is never a yes").isFalse();
        }
        if (answer == Answer.YES && !conditional && !terms) {
            assertThat(CompoundReply.confirmsDeal(turn)).as(turn + " confirms a read-back").isTrue();
        }
    }

    // ---- round 4: a small closed list of benign acceptance tails, and the qualified family still denied ----

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sure thing", "ಹೌದು ಇಟ್ಕೊಳ್ಳಿ", "yes please proceed", "yes please", "yes pls", "yes kindly proceed",
            "yes go ahead", "yes do it", "yes carry on", "yes sir", "yes madam", "haan ji", "haan saheb", "yes of course",
            "yes absolutely", "yes fine", "yes good", "yes okay", "yes ok", "haan theek hai", "haan bilkul", "haan laga do",
            "haan kar do", "haan rakh lo", "haan rakh lijiye", "haan rakh sakte hain", "ಹೌದು ಮಾಡಿ", "ಹೌದು ಆಗಲಿ",
            "হ্যাঁ রাখতে পারেন", "হ্যাঁ রাখুন", "হ্যাঁ করুন", "হ্যাঁ ঠিক আছে", "ஆமா வைத்துக்கொள்ளுங்கள்", "ஆமா செய்யுங்கள்",
            "ஆமா சரி", "yes I think it is fine", "yes well I think it is fine", "yes um okay", "haan ji bilkul theek hai sir",
            "हाँ जी, ठीक है", "sure, go ahead please"})
    void aBenignAcceptanceTailKeepsThePlainYes(String turn) {
        expect(turn, Answer.YES, false, false, "");
        assertThat(CompoundReply.consents(turn)).as(turn).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "yes tomorrow", "yes on delivery", "yes after payment", "yes only if he pays", "yes but not today",
            "haan kal", "haan delivery pe", "haan payment ke baad", "haan sirf agar woh de", "haan lekin aaj nahi",
            "हाँ कल", "हाँ डिलीवरी पर", "हाँ पेमेंट के बाद", "हाँ सिर्फ़ अगर वो दें", "हाँ लेकिन आज नहीं",
            "হ্যাঁ কাল", "হ্যাঁ ডেলিভারির সময়", "হ্যাঁ টাকা দেওয়ার পরে", "হ্যাঁ শুধু যদি দেয়", "হ্যাঁ কিন্তু আজ না",
            "ஆமா நாளைக்கு", "ஆமா டெலிவரி போது", "ஆமா பணம் கொடுத்த பிறகு", "ஆமா கொடுத்தால் மட்டும்", "ஆமா ஆனா இன்று இல்லை",
            "ಹೌದು ನಾಳೆ", "ಹೌದು ಡೆಲಿವರಿ ಆದಾಗ", "ಹೌದು ಹಣ ಕೊಟ್ಟ ನಂತರ", "ಹೌದು ಕೊಟ್ಟರೆ ಮಾತ್ರ", "ಹೌದು ಆದರೆ ಇವತ್ತು ಅಲ್ಲ",
            // A benign tail never launders a qualification beside it.
            "yes please, tomorrow", "sure thing, on delivery", "ಹೌದು ಇಟ್ಕೊಳ್ಳಿ ನಾಳೆ", "theek hai kal", "yes go ahead after payment",
            "haan laga do kal", "yes fine but only if he pays", "yes I think it is fine tomorrow"})
    void theQualifiedFamilyStillFailsClosed(String turn) {
        Parsed p = CompoundReply.parse(turn);
        assertThat(p.answer()).as(turn).isEqualTo(Answer.NONE);
        assertThat(p.conditional()).as(turn).isTrue();
        assertThat(CompoundReply.permitsYes(turn)).as(turn).isFalse();
        assertThat(CompoundReply.consents(turn)).as(turn + ": never consent, whatever the lexicon says").isFalse();
    }

    @Test
    void termsAndConservativeQuestionsStayAsTheyWere() {
        assertThat(CompoundReply.parse("yes 4500 per quintal").termsStated()).isTrue();
        assertThat(CompoundReply.confirmsDeal("yes please, 4500 per quintal")).isFalse();
        assertThat(CompoundReply.parse("yes what happens if he refuses?").answer()).isEqualTo(Answer.NONE);
        assertThat(CompoundReply.consents("unse baat karao")).as("a plain request to connect").isTrue();
        assertThat(CompoundReply.consents("phone laga do, unka naam kya hai?")).isTrue();
        assertThat(CompoundReply.consents("unse baat karao kal")).isFalse();
    }
}
