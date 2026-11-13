package com.example.g_one.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The closed-value mappers of a stored deal card, in English, romanised Hindi, Hindi, Bengali, Tamil and Kannada. */
class DealCardTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DealVocabulary VOCAB = DealVocabulary.bundled(JSON);

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "the buyer collects on Friday | buyer_collects",
        "truck se uthayega | buyer_collects",
        "व्यापारी ले जाएगा | buyer_collects",
        "ওরা নিয়ে যাবে | buyer_collects",
        "அவர் எடுத்துச் செல்வார் | buyer_collects",
        "ಅವರು ತೆಗೆದುಕೊಂಡು ಹೋಗುತ್ತಾರೆ | buyer_collects",
        "I will deliver to the mandi | farmer_delivers",
        "main mandi pahuncha dunga | farmer_delivers",
        "मैं गोदाम तक पहुँचा दूँगा | farmer_delivers",
        "আমি পৌঁছে দেব | farmer_delivers",
        "நான் கொண்டு வருவேன் | farmer_delivers",
        "ನಾನು ಮಂಡಿಗೆ ತಂದು ಕೊಡುತ್ತೇನೆ | farmer_delivers",
        "Friday at the farm | at_farm",
        "khet par | at_farm",
        "खेत पर | at_farm",
        "জমি থেকে | at_farm",
        "வயலில் | at_farm",
        "ಹೊಲದಲ್ಲಿ | at_farm",
        "next Tuesday morning | other",
    })
    void pickupKinds(String spoken, String kind) {
        assertThat(DealCard.pickup(spoken).wire()).isEqualTo(kind);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "cash on pickup | on_delivery",
        "on delivery | on_delivery",
        "haathon haath | on_delivery",
        "डिलीवरी पर | on_delivery",
        "হাতে হাতে | on_delivery",
        "டெலிவரி அன்று | on_delivery",
        "ಡೆಲಿವರಿ ಸಮಯದಲ್ಲಿ | on_delivery",
        "full advance | advance",
        "अग्रिम | advance",
        "অগ্রিম | advance",
        "முன்பணம் | advance",
        "ಮುಂಗಡ | advance",
        "half advance, rest later | partial_advance",
        "aadha pehle | partial_advance",
        "आधा अभी | partial_advance",
        "কিছু অগ্রিম | partial_advance",
        "பாதி இப்போது | partial_advance",
        "ಅರ್ಧ ಈಗ | partial_advance",
        "after seven days | days_after",
        "das din baad | days_after",
        "15 दिन में | days_after",
        "সাত দিন পরে | days_after",
        "ஏழு நாள் கழித்து | days_after",
        "ಏಳು ದಿನ ನಂತರ | days_after",
        "by UPI | upi",
        "GPay se | upi",
        "ऑनलाइन | upi",
        "অনলাইনে | upi",
        "ஆன்லைனில் | upi",
        "ಆನ್‌ಲೈನ್ ಮೂಲಕ | upi",
        "cash | cash",
        "nakad | cash",
        "नकद | cash",
        "নগদ | cash",
        "ரொக்கம் | cash",
        "ನಗದು | cash",
        "whatever suits | other",
    })
    void paymentKinds(String spoken, String kind) {
        assertThat(DealCard.payment(spoken).wire()).isEqualTo(kind);
    }

    @Test
    void nothingSaidIsUnspecified() {
        assertThat(DealCard.pickup(null)).isEqualTo(DealCard.Pickup.UNSPECIFIED);
        assertThat(DealCard.payment("  ")).isEqualTo(DealCard.Payment.UNSPECIFIED);
        assertThat(DealCard.pickupLabel(DealCard.Pickup.UNSPECIFIED, DealCard.Payment.UNSPECIFIED)).isNull();
        assertThat(DealCard.pickupLabel(DealCard.Pickup.BUYER_COLLECTS, DealCard.Payment.UPI))
                .isEqualTo("Buyer collects; payment by UPI");
    }

    @ParameterizedTest
    @ValueSource(strings = {"call me 1234567890", "+91 98765 43210", "98765-43210", "(022) 2345 6789",
        "९८७६५४३२१०", "mail me at kisan@example.org", "https://pay.example.com/x", "www.example.in", "visit kisan.in"})
    void unsafeTextIsNeverMapped(String spoken) {
        assertThat(DealCard.unsafe(spoken)).isTrue();
        assertThat(DealCard.pickup(spoken)).isNull();
        assertThat(DealCard.payment(spoken)).isNull();
        assertThat(DealCard.quantity(spoken)).isNull();
        assertThat(VOCAB.crop(spoken)).isNull();
        assertThat(VOCAB.market(spoken)).isNull();
        assertThat(VOCAB.buyer(spoken)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10 quintal", "Friday at the farm", "4500 rupees", "pin 422001"})
    void ordinaryTermsAreSafe(String spoken) {
        assertThat(DealCard.unsafe(spoken)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "10 quintal | 10 | quintal",
        "ten quintals | 10 | quintal",
        "2,500 kg | 2500 | kg",
        "१० क्विंटल | 10 | quintal",
        "২০ কেজি | 20 | kg",
        "5 டன் | 5 | tonne",
        "30 ಚೀಲ | 30 | bag",
        "12 dozen | 12 | dozen",
        "40 बोरी | 40 | bag",
    })
    void quantitiesInListedUnits(String spoken, String value, String unit) {
        DealCard.Quantity q = DealCard.quantity(spoken);
        assertThat(q).isNotNull();
        assertThat(q.value()).isEqualByComparingTo(value);
        assertThat(q.unit().wire).isEqualTo(unit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a lot", "10 truckloads", "0 kg", "-5 kg", "99999999 kg", "some onions", "ten"})
    void otherQuantitiesAreLeftOut(String spoken) {
        assertThat(DealCard.quantity(spoken)).isNull();
    }

    @Test
    void cropsMarketsAndBuyersComeFromTheLists() {
        assertThat(VOCAB.crop("onion")).isEqualTo("Onion");
        assertThat(VOCAB.crop("प्याज")).isEqualTo("Onion");
        assertThat(VOCAB.crop("dragon fruit")).isEqualTo("other");
        assertThat(VOCAB.crop(null)).isNull();
        assertThat(VOCAB.market("Lasalgaon")).isEqualTo("Lasalgaon");
        assertThat(VOCAB.market("Burdwan mandi")).isEqualTo("Burdwan APMC");
        assertThat(VOCAB.market("বর্ধমান")).isEqualTo("Burdwan APMC");
        assertThat(VOCAB.market("my uncle's shop")).isEqualTo("other");
        assertThat(VOCAB.buyer("mehta-agro")).map(b -> b.name()).contains("Mehta Agro Traders");
        assertThat(VOCAB.buyer("buyer-not-in-directory")).isEmpty();
    }

    @Test
    void priceNeedsABoundedValueAndAListedBasis() {
        ObjectNode ok = JSON.createObjectNode();
        DealCard.fill(ok, VOCAB, "onion", null, "10 quintal", new BigDecimal("2100.00"), "qtl", null, null);
        assertThat(ok.get("price").decimalValue()).isEqualByComparingTo("2100");
        assertThat(ok.get("unit").asText()).isEqualTo("quintal");
        assertThat(ok.get("basis").asText()).isEqualTo("per quintal");
        assertThat(ok.get("total").decimalValue()).isEqualByComparingTo("21000");
        assertThat(ok.get("pickup").isNull()).isTrue();

        ObjectNode total = JSON.createObjectNode();
        DealCard.fill(total, VOCAB, "onion", null, "10 quintal", new BigDecimal("20000"), "total", null, null);
        assertThat(total.get("priceBasis").asText()).isEqualTo("total");
        assertThat(total.get("total").decimalValue()).isEqualByComparingTo("20000");

        ObjectNode kgFromQuintal = JSON.createObjectNode();
        DealCard.fill(kgFromQuintal, VOCAB, "onion", null, "2 quintal", new BigDecimal("21"), "kg", null, null);
        assertThat(kgFromQuintal.get("total").decimalValue()).isEqualByComparingTo("4200");

        for (String unit : new String[] {"per truck", "lorry", "call 1234567"}) {
            ObjectNode bad = JSON.createObjectNode();
            DealCard.fill(bad, VOCAB, "onion", null, "10 quintal", new BigDecimal("2100"), unit, null, null);
            assertThat(bad.get("price").isNull()).as(unit).isTrue();
            assertThat(bad.get("unit").isNull()).as(unit).isTrue();
        }
        ObjectNode huge = JSON.createObjectNode();
        DealCard.fill(huge, VOCAB, "onion", null, null, new BigDecimal("98765432100"), "kg", null, null);
        assertThat(huge.get("price").isNull()).isTrue();
    }
}
