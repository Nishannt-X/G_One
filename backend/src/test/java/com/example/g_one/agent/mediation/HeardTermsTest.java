package com.example.g_one.agent.mediation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.deal.Offer;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A term of the model's reading counts for a party only when the words they said or heard carry it. */
class HeardTermsTest {
    private static final Offer QUANTITY = new Offer("none", null, null, null, null, null, null, "50 quintal", null, null);
    private static final Offer PRICE = new Offer("clear", "Onion", null, null, new BigDecimal("4000"), "INR", "quintal");

    @ParameterizedTest(name = "[{index}] {1}: {0} -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            I have fifty quintals                        | en-IN | true
            I have 50 quintal                            | en-IN | true
            I have as much as I told you                 | en-IN | false
            I have fifty bags                            | en-IN | false
            मेरे पास पचास क्विंटल है                        | hi-IN | true
            मेरे पास बहुत माल है                            | hi-IN | false
            আমার পঞ্চাশ কুইন্টাল আছে                        | bn-IN | true
            என்னிடம் ஐம்பது குவிண்டால் உள்ளது              | ta-IN | true
            ನನ್ನ ಬಳಿ ಐವತ್ತು ಕ್ವಿಂಟಲ್ ಇದೆ                      | kn-IN | true
            """)
    void aQuantityNeedsItsFigureAndUnit(String text, String code, boolean kept) {
        Offer o = HeardTerms.in(QUANTITY, text, code);
        assertThat(o != null && o.quantity() != null).isEqualTo(kept);
    }

    @ParameterizedTest(name = "[{index}] {1}: {0} -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            मैं चार हज़ार रुपये प्रति क्विंटल दे सकता हूँ       | hi-IN | true
            मैं चार हज़ार दे सकता हूँ                         | hi-IN | false
            I can pay 4000 per quintal                    | en-IN | true
            I will take it at the rate we discussed       | en-IN | false
            """)
    void aPriceNeedsItsFigureAndUnit(String text, String code, boolean kept) {
        Offer o = HeardTerms.in(PRICE, text, code);
        assertThat(o != null && o.price() != null).isEqualTo(kept);
    }
}
