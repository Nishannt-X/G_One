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
            "Yes, G-One, what quantity did we discuss?|YES|G-One, what quantity did we discuss?",
            "haan main Suresh hoon, bhav kya hai|YES|bhav kya hai",
            // A no, then a question: the no stands and the question is handled.
            "nahi, unka naam kya hai?|NO|unka naam kya hai?",
            "no, but what is the price today?|NO|what is the price today?",
            "नहीं, आज का भाव क्या है?|NO|आज का भाव क्या है?",
            "না, আজকের দাম কত?|NO|আজকের দাম কত?",
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
        assertThat(CompoundReply.parse("haan, kitne quintal the?").termsStated()).as("asked, not stated").isFalse();
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
}
