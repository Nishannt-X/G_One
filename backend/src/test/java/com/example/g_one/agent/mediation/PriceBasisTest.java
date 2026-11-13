package com.example.g_one.agent.mediation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PriceBasisTest {
    private static final BigDecimal TWO_THOUSAND = new BigDecimal("2000");

    @Test
    void acceptsOnlyAnExplicitUnambiguousBasis() {
        assertThat(PriceBasis.read("10 kg rice for 2000", "en-IN")).isNull();
        assertThat(PriceBasis.read("2000 per kg", "en-IN")).isEqualTo("kg");
        assertThat(PriceBasis.read("दो हजार प्रति क्विंटल", "hi-IN")).isEqualTo("quintal");
        assertThat(PriceBasis.read("2000 total for all of it", "en-IN")).isEqualTo("total");
        assertThat(PriceBasis.read("total or per kg?", "en-IN")).isNull();
        assertThat(PriceBasis.conflicting("total or per kg?", "en-IN")).isTrue();
        assertThat(PriceBasis.conditional("not per kg")).isTrue();
        assertThat(PriceBasis.evidence("10 per kg last year, today 2000", "en-IN", TWO_THOUSAND)).isNull();
        assertThat(PriceBasis.evidence("10 kg rice, 2000 per kg", "en-IN", TWO_THOUSAND)).isEqualTo("kg");
    }

    @Test
    void quintalAndKiloStayDistinct() {
        assertThat(PriceBasis.evidence("10 kg onion, 2000 rupees per quintal", "en-IN", TWO_THOUSAND)).isEqualTo("quintal");
        assertThat(PriceBasis.evidence("20 quintals at 2000 rupees a kilo", "en-IN", TWO_THOUSAND)).isEqualTo("kg");
        assertThat(PriceBasis.evidence("2000 rupees for 10 kg", "en-IN", TWO_THOUSAND)).isNull();
    }

    @Test
    void rateFirstOrderingIsARateOnlyWithoutALotQuantity() {
        assertThat(PriceBasis.evidence("किलो के दो हज़ार रुपये", "hi-IN", TWO_THOUSAND)).isEqualTo("kg");
        assertThat(PriceBasis.evidence("kilo ke 2000 rupaye", "hi-IN", TWO_THOUSAND)).isEqualTo("kg");
        assertThat(PriceBasis.evidence("एक किलो के दो हज़ार रुपये", "hi-IN", TWO_THOUSAND)).isEqualTo("kg");
        assertThat(PriceBasis.evidence("दस किलो के दो हज़ार रुपये", "hi-IN", TWO_THOUSAND)).isNull();
        assertThat(PriceBasis.evidence("das kilo ke do hazaar", "hi-IN", TWO_THOUSAND)).isNull();
        assertThat(PriceBasis.evidence("ten kilo ke 2000", "en-IN", TWO_THOUSAND)).isNull();
        assertThat(PriceBasis.read("10 किलो का प्याज़, 2000 रुपये प्रति किलो", "hi-IN")).isEqualTo("kg");
        assertThat(PriceBasis.evidence("কেজি প্রতি ২০০০ টাকা", "bn-IN", TWO_THOUSAND)).isEqualTo("kg");
        assertThat(PriceBasis.evidence("दो हज़ार रुपये किलो", "hi-IN", TWO_THOUSAND)).isEqualTo("kg");
        assertThat(PriceBasis.evidence("दो हज़ार रुपये किलो के हिसाब से", "hi-IN", TWO_THOUSAND)).isEqualTo("kg");
        assertThat(PriceBasis.evidence("ஒரு கிலோவுக்கு 2000 ரூபாய்", "ta-IN", TWO_THOUSAND)).isEqualTo("kg");
        assertThat(PriceBasis.evidence("ಕೆಜಿಗೆ 2000 ರೂಪಾಯಿ", "kn-IN", TWO_THOUSAND)).isEqualTo("kg");
    }

    @Test
    void currencyIsReadFromTheWordsInEveryLanguage() {
        for (String rupees : new String[] {"2000 rupees per kg", "2000 rupaiya per kg", "₹2000 per kg", "Rs. 2000 per kg",
                "दो हज़ार रुपये प्रति किलो", "do hazaar rupaye kilo", "প্রতি কেজি ২০০০ টাকা", "ஒரு கிலோவுக்கு 2000 ரூபாய்",
                "ಕೆಜಿಗೆ 2000 ರೂಪಾಯಿ"}) {
            assertThat(PriceBasis.currency(rupees)).as(rupees).isEqualTo(PriceBasis.Currency.RUPEE);
        }
        for (String foreign : new String[] {"2000 dollars per kg", "2000 USD per kg", "$2000 per kg", "2000 euros a kilo",
                "€2000", "2000 pounds", "do hazaar dollar per kilo", "दो हज़ार डॉलर प्रति किलो", "প্রতি কেজি ২০০০ ডলার",
                "ஒரு கிலோவுக்கு 2000 டாலர்", "ಕೆಜಿಗೆ 2000 ಡಾಲರ್", "2000 rupees, I mean dollars"}) {
            assertThat(PriceBasis.currency(foreign)).as(foreign).isEqualTo(PriceBasis.Currency.FOREIGN);
        }
        for (String none : new String[] {"I will pay 2000 per kg", "do hazaar kilo ke", "रुकिए, दो हज़ार", "hours"}) {
            assertThat(PriceBasis.currency(none)).as(none).isEqualTo(PriceBasis.Currency.NONE);
        }
    }

    @Test
    void aShortReplyAnswersTheQuestionAndAnythingElseDoesNot() {
        assertThat(PriceBasis.answer("per kg", "en-IN")).isEqualTo(new PriceBasis.Answer("kg", false));
        assertThat(PriceBasis.answer("total", "en-IN")).isEqualTo(new PriceBasis.Answer("total", false));
        assertThat(PriceBasis.answer("in rupees per kilo", "en-IN")).isEqualTo(new PriceBasis.Answer("kg", true));
        assertThat(PriceBasis.answer("yes", "en-IN")).isEqualTo(new PriceBasis.Answer(null, true));
        assertThat(PriceBasis.answer("haan ji, rupaye", "hi-IN")).isEqualTo(new PriceBasis.Answer(null, true));
        assertThat(PriceBasis.answer("प्रति क्विंटल", "hi-IN")).isEqualTo(new PriceBasis.Answer("quintal", false));
        assertThat(PriceBasis.answer("kilo", "hi-IN")).isEqualTo(new PriceBasis.Answer("kg", false));
        assertThat(PriceBasis.answer("किलो के हिसाब से", "hi-IN")).isEqualTo(new PriceBasis.Answer("kg", false));
        assertThat(PriceBasis.answer("quintal ka", "hi-IN")).isEqualTo(new PriceBasis.Answer("quintal", false));
        assertThat(PriceBasis.answer("dollars per kg", "en-IN")).isNull();
        assertThat(PriceBasis.answer("per kg if I like it", "en-IN")).isNull();
        assertThat(PriceBasis.answer("2500 per kg", "en-IN")).isNull();
        assertThat(PriceBasis.answer("per kg or total", "en-IN")).isNull();
        assertThat(PriceBasis.answer("I will pick it up tomorrow", "en-IN")).isNull();
    }

    @Test
    void aTranslationMustKeepEveryNumberOnItsOwnUnit() {
        String source = "10 kg onion, 2000 rupees per kg";
        assertThat(PriceBasis.faithful(source, "en-IN", "दस किलो प्याज़, दो हज़ार रुपये", "hi-IN", TWO_THOUSAND, "kg")).isTrue();
        assertThat(PriceBasis.faithful(source, "en-IN", "दस किलो प्याज़, दो हज़ार रुपये प्रति किलो", "hi-IN", TWO_THOUSAND, "kg"))
                .isTrue();
        assertThat(PriceBasis.faithful(source, "en-IN", "दो हज़ार किलो प्याज़, दस रुपये", "hi-IN", TWO_THOUSAND, "kg")).isFalse();
        assertThat(PriceBasis.faithful(source, "en-IN", "दो हज़ार किलो प्याज़, दस रुपये प्रति किलो", "hi-IN", TWO_THOUSAND, "kg"))
                .isFalse();
        assertThat(PriceBasis.faithful(source, "en-IN", "दस किलो के दो हज़ार रुपये", "hi-IN", TWO_THOUSAND, "kg")).isFalse();
        assertThat(PriceBasis.faithful(source, "en-IN", "दस क्विंटल प्याज़, दो हज़ार रुपये", "hi-IN", TWO_THOUSAND, "kg")).isFalse();
        assertThat(PriceBasis.faithful(source, "en-IN", "दस किलो प्याज़, दो हज़ार डॉलर", "hi-IN", TWO_THOUSAND, "kg")).isFalse();
        assertThat(PriceBasis.faithful("2000 rupees total", "en-IN", "कुल दो हज़ार रुपये", "hi-IN", TWO_THOUSAND, "total")).isTrue();
        assertThat(PriceBasis.faithful("2000 rupees total", "en-IN", "दो हज़ार रुपये प्रति किलो", "hi-IN", TWO_THOUSAND, "total"))
                .isFalse();
    }
}
