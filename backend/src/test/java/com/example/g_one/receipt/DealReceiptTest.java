package com.example.g_one.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.ConsoleEvent.DealState;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DealReceiptTest {

    private ConsoleBus bus;
    private ReceiptStore store;
    private DealReceiptController controller;

    @BeforeEach
    void setUp() {
        bus = new ConsoleBus();
        store = new ReceiptStore(bus);
        controller = new DealReceiptController(store);
    }

    @Test
    void capturesDealUpdatedFromConsoleBus() {
        ConsoleEvent.DealUpdated deal = new ConsoleEvent.DealUpdated(
                CallId.of("call-42"),
                1731060000000L,
                DealState.AGREED,
                "Onion",
                "Garwa",
                "Lasalgaon APMC",
                "50 quintal",
                BigDecimal.valueOf(2400),
                "quintal",
                "buyer pickup from farm",
                true,
                true
        );

        bus.publish(deal);

        var rOpt = store.get("call-42");
        assertThat(rOpt).isPresent();
        DealReceipt r = rOpt.get();
        assertThat(r.id()).isEqualTo("call-42");
        assertThat(r.commodity()).isEqualTo("Onion");
        assertThat(r.variety()).isEqualTo("Garwa");
        assertThat(r.market()).isEqualTo("Lasalgaon APMC");
        assertThat(r.price()).isEqualByComparingTo(BigDecimal.valueOf(2400));
        assertThat(r.farmerYes()).isTrue();
        assertThat(r.buyerYes()).isTrue();
        assertThat(r.state()).isEqualTo("agreed");
        assertThat(r.wording()).isEqualTo("recorded by G-One from the call");
    }

    @Test
    void getReceiptJsonReturns200ForExistingDeal() {
        DealReceipt receipt = DealReceipt.of(
                "deal-101",
                "agreed",
                "Wheat",
                "Lokwan",
                "Karnal Mandi",
                "20 quintal",
                BigDecimal.valueOf(2550),
                "quintal",
                "farm-gate pickup",
                true,
                true,
                1731060000000L
        );
        store.save(receipt);

        var response = controller.getReceiptJson("deal-101");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DealReceipt body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.commodity()).isEqualTo("Wheat");
        assertThat(body.wording()).isEqualTo("recorded by G-One from the call");
    }

    @Test
    void getReceiptJsonReturns404ForMissingDeal() {
        var response = controller.getReceiptJson("non-existent");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getReceiptHtmlRendersBilingualSaudaParchi() {
        DealReceipt receipt = DealReceipt.of(
                "call-88",
                "agreed",
                "Tomato",
                "Hybrid",
                "Pimpalgaon",
                "30 crates",
                BigDecimal.valueOf(1800),
                "quintal",
                "buyer pickup",
                true,
                true,
                1731060000000L
        );
        store.save(receipt);

        var response = controller.getReceiptHtml("call-88");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String html = response.getBody();
        assertThat(html).isNotNull();
        assertThat(html).contains("सौदा पर्ची / Sauda Parchi");
        assertThat(html).contains("recorded by G-One from the call");
        assertThat(html).contains("Tomato");
        assertThat(html).contains("Hybrid");
        assertThat(html).contains("₹ 1800");
        assertThat(html).contains("हाँ / Yes");
        assertThat(html).contains("गैर-बाध्यकारी");
    }

    @Test
    void getReceiptHtmlReturns404ForMissingDeal() {
        var response = controller.getReceiptHtml("missing-call");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void simulatedSmsOutboxIsProperlyLabelled() {
        DealReceipt receipt = DealReceipt.of(
                "call-77",
                "agreed",
                "Paddy",
                "Basmati",
                "Karnal",
                "40 quintal",
                BigDecimal.valueOf(3200),
                "quintal",
                "mandi",
                true,
                true,
                1731060000000L
        );
        store.save(receipt);

        var postRes = controller.sendSimulatedSms("call-77", "+919876543210", Map.of("phone", "+919876543210"));
        assertThat(postRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        SimulatedSms sms = postRes.getBody();
        assertThat(sms).isNotNull();
        assertThat(sms.status()).isEqualTo("simulated, nothing sent");
        assertThat(sms.note()).isEqualTo("simulated, nothing sent");
        assertThat(sms.messageText()).contains("recorded by G-One from the call");

        // Verify outbox endpoints
        var dealOutbox = controller.getDealSms("call-77");
        assertThat(dealOutbox).hasSize(1);
        assertThat(dealOutbox.getFirst().recipientPhone()).isEqualTo("+919876543210");

        var allOutbox = controller.getAllSms();
        assertThat(allOutbox).hasSize(1);
    }

    @Test
    void honestWordingEnforcedInReceipts() {
        DealReceipt receipt = DealReceipt.of(
                "call-99", "agreed", "Soybean", "JS 335", "Indore", "25 quintal",
                BigDecimal.valueOf(4400), "quintal", "farm-gate", true, true, 1731060000000L
        );
        String html = SaudaParchiRenderer.renderHtml(receipt).toLowerCase();
        for (String forbidden : new String[]{"verified", "real time", "instant", "blocks scams", "guaranteed"}) {
            assertThat(html).doesNotContain(forbidden);
        }
        assertThat(html).contains("recorded by g-one from the call");
    }
}
