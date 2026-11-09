package com.example.g_one.agent.mediation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.agent.caution.Caution;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScamPatternsTest {
    @ParameterizedTest
    @ValueSource(strings = {"Please tell me the OTP", "apna otp bata do", "पिन डालिए पैसे मिल जाएंगे", "type your PIN to receive money",
            "ওটিপি বলুন", "Scan this QR code and enter your UPI PIN to receive the advance", "Scan this QR to receive your payment",
            "yeh QR scan karo paisa mil jayega", "approve the collect request to receive the money", "OTP bata do", "ओटीपी बता दो"})
    void asksForASecret(String s) {
        assertThat(ScamPatterns.buyer(s)).contains(Caution.SECRET_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"You must pay a registration fee first", "aap pehle advance bhej do", "आप पहले जमा कर दीजिए",
            "send the transport charge first, you pay it", "send 500 rupees advance first"})
    void asksForMoneyFirst(String s) {
        assertThat(ScamPatterns.buyer(s)).contains(Caution.ADVANCE_PAYMENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"I will pay you an advance of five thousand", "main aapko advance de dunga", "What is your UPI ID",
            "I can pay four thousand per quintal", "Tell me your account number for payment",
            "aapko advance de dunga", "आपके खाते में पैसा जमा कर दूँगा", "Tell me your pin code for pickup", "apna pin code bata do",
            "पिन कोड बता दीजिए", "Fine, twenty two hundred, pay on delivery, pickup tomorrow. Deal.", "cash on delivery", "delivery pe paisa dunga",
            "maal uthne par payment", "cash me dunga", "UPI se baad me bhej dunga", "I will scan your QR and you will receive the money",
            "apna QR bhejo, main scan karke payment kar dunga"})
    void leavesOrdinaryTradeTalkAlone(String s) {
        assertThat(ScamPatterns.buyer(s)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Fine, twenty two hundred, pay on delivery, pickup tomorrow. Deal.", "Cash on delivery", "2200, payment on pickup",
            "UPI transfer after delivery", "delivery pe paisa dunga", "maal uthne par payment", "cash me dunga",
            "UPI se baad me bhej dunga", "डिलीवरी पर पैसा दूँगा", "माल उठने पर पेमेंट", "नकद में दूँगा", "token de dunga aapko"})
    void aModelFlagOnOrdinaryTermsIsDropped(String s) {
        assertThat(ScamPatterns.supported(EnumSet.of(Caution.SECRET_REQUEST, Caution.ADVANCE_PAYMENT), s)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Pay on delivery but first send the OTP", "Cash on delivery, pay a 500 fee", "delivery pe paisa, pehle QR scan karo",
            "Could you tell me the number that came on your phone"})
    void aModelFlagStandsWhenTheTurnCarriesMoreThanOrdinaryTerms(String s) {
        assertThat(ScamPatterns.supported(Set.of(Caution.SECRET_REQUEST), s)).containsExactly(Caution.SECRET_REQUEST);
    }

    @Test
    void anOrdinaryTurnKeepsAThreatFlag() {
        assertThat(ScamPatterns.supported(Set.of(Caution.THREAT), "cash on delivery")).containsExactly(Caution.THREAT);
    }

    @Test
    void aThreatIsNoticed() {
        assertThat(ScamPatterns.buyer("I will beat you if you say no")).contains(Caution.THREAT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mera OTP 482913 hai", "पिन 4821", "1234 5678 9012"})
    void aSecretTheFarmerSays(String s) {
        assertThat(ScamPatterns.farmerSharedSecret(s)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bees quintal", "4300 per quintal", "mera pin code nahi pata", "pin code 411001"})
    void notASecret(String s) {
        assertThat(ScamPatterns.farmerSharedSecret(s)).isFalse();
    }
}
