package com.example.g_one.advisory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallContext;
import com.example.g_one.core.CallId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ScamShieldTest {
    private final ScamShield shield = new ScamShield();
    private final ScamShieldTool tool = new ScamShieldTool();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void detectsQrCodeScamInEnglish() {
        var d = shield.screen("Scan this QR code on WhatsApp to receive the payment into your account");
        assertThat(d.detected()).isTrue();
        assertThat(d.category()).isEqualTo(ScamShield.Category.QR_CODE);
        assertThat(d.severity()).isEqualTo(ScamShield.Severity.CRITICAL);
        assertThat(d.whisperTextEn()).contains("Scanning a QR code sends money");
        assertThat(d.whisperTextHi()).contains("क्यूआर कोड");
    }

    @Test
    void detectsQrCodeScamInHinglish() {
        var d = shield.screen("Main QR code bhej raha hoon, scan kar lo paise mil jayenge");
        assertThat(d.detected()).isTrue();
        assertThat(d.category()).isEqualTo(ScamShield.Category.QR_CODE);
    }

    @Test
    void detectsQrCodeScamInDevanagari() {
        var d = shield.screen("यह क्यूआर कोड स्कैन करो, तुरंत खाते में पैसे आ जाएंगे");
        assertThat(d.detected()).isTrue();
        assertThat(d.category()).isEqualTo(ScamShield.Category.QR_CODE);
    }

    @Test
    void detectsUpiPinScamInEnglish() {
        var d = shield.screen("Please enter your UPI PIN to accept the funds into your bank account");
        assertThat(d.detected()).isTrue();
        assertThat(d.category()).isEqualTo(ScamShield.Category.UPI_PIN);
        assertThat(d.severity()).isEqualTo(ScamShield.Severity.CRITICAL);
    }

    @Test
    void detectsUpiPinScamInHinglish() {
        var d = shield.screen("Apna UPI PIN daalo tabhi account me transfer hoga");
        assertThat(d.detected()).isTrue();
        assertThat(d.category()).isEqualTo(ScamShield.Category.UPI_PIN);
    }

    @Test
    void detectsUpiPinScamInDevanagari() {
        var d = shield.screen("पैसे लेने के लिए अपना यूपीआई पिन दर्ज करें");
        assertThat(d.detected()).isTrue();
        assertThat(d.category()).isEqualTo(ScamShield.Category.UPI_PIN);
    }

    @Test
    void detectsOtpScamInEnglish() {
        var d = shield.screen("Tell me the OTP sent to your mobile to verify the payment");
        assertThat(d.detected()).isTrue();
        assertThat(d.category()).isEqualTo(ScamShield.Category.OTP);
        assertThat(d.severity()).isEqualTo(ScamShield.Severity.CRITICAL);
    }

    @Test
    void detectsOtpScamInHinglish() {
        var d = shield.screen("Aapke phone par OTP aaya hai, jaldi bata do");
        assertThat(d.detected()).isTrue();
        assertThat(d.category()).isEqualTo(ScamShield.Category.OTP);
    }

    @Test
    void detectsOtpScamInDevanagari() {
        var d = shield.screen("अपने मोबाइल पर आया ओटीपी बताओ");
        assertThat(d.detected()).isTrue();
        assertThat(d.category()).isEqualTo(ScamShield.Category.OTP);
    }

    @Test
    void detectsAdvanceFeeInEnglish() {
        var d = shield.screen("Pay a registration fee of ₹500 before we can send the truck");
        assertThat(d.detected()).isTrue();
        assertThat(d.category()).isEqualTo(ScamShield.Category.ADVANCE_FEE);
        assertThat(d.severity()).isEqualTo(ScamShield.Severity.HIGH);
    }

    @Test
    void detectsAdvanceFeeInHinglish() {
        var d = shield.screen("Pehle 2000 deposit karo booking ke liye");
        assertThat(d.detected()).isTrue();
        assertThat(d.category()).isEqualTo(ScamShield.Category.ADVANCE_FEE);
    }

    @Test
    void detectsAdvanceFeeInDevanagari() {
        var d = shield.screen("पहले टोकन के 1000 रुपये जमा कीजिए तब माल उठाएंगे");
        assertThat(d.detected()).isTrue();
        assertThat(d.category()).isEqualTo(ScamShield.Category.ADVANCE_FEE);
    }

    @Test
    void detectsUrgencyPressureInEnglish() {
        var d = shield.screen("This offer expires in 5 minutes, decide immediately or deal will be cancelled");
        assertThat(d.detected()).isTrue();
        assertThat(d.category()).isEqualTo(ScamShield.Category.URGENCY_PRESSURE);
        assertThat(d.severity()).isEqualTo(ScamShield.Severity.MEDIUM);
    }

    @Test
    void detectsUrgencyPressureInHinglish() {
        var d = shield.screen("Abhi ke abhi haan bolo warna deal cancel ho jayegi");
        assertThat(d.detected()).isTrue();
        assertThat(d.category()).isEqualTo(ScamShield.Category.URGENCY_PRESSURE);
    }

    @Test
    void detectsUrgencyPressureInDevanagari() {
        var d = shield.screen("तुरंत फैसला कीजिए, अभी नहीं तो सौदा रद्द हो जाएगा");
        assertThat(d.detected()).isTrue();
        assertThat(d.category()).isEqualTo(ScamShield.Category.URGENCY_PRESSURE);
    }

    @Test
    void normalOfferIsNotFlaggedAsScam() {
        var d = shield.screen("Main 2500 rupay per quintal dunga, kal subah truck bhej denge");
        assertThat(d.detected()).isFalse();
        assertThat(d.category()).isEqualTo(ScamShield.Category.NONE);
    }

    @Test
    void normalFarmerStatementIsNotFlagged() {
        var d = shield.screen("Hamare paas 50 quintal gehun hai badhiya quality ka");
        assertThat(d.detected()).isFalse();
    }

    @Test
    void toolInvocationWorks() throws Exception {
        var args = JSON.createObjectNode().put("text", "QR code scan karo paise milenge");
        ToolResult res = tool.invoke(args, null);
        assertThat(res.ok()).isTrue();
        var json = JSON.readTree(res.content());
        assertThat(json.get("detected").asBoolean()).isTrue();
        assertThat(json.get("category").asText()).isEqualTo("QR_CODE");
        assertThat(json.get("whisperTextHi").asText()).contains("क्यूआर कोड");
    }
}
