package com.example.g_one.receipt;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for retrieving deal receipts, rendering bilingual HTML "Sauda Parchi",
 * and inspecting the simulated SMS outbox.
 */
@RestController
public class DealReceiptController {

    private final ReceiptStore store;

    @Autowired
    public DealReceiptController(ReceiptStore store) {
        this.store = store;
    }

    /**
     * GET /api/deals/{id}/receipt: JSON representation of the deal receipt recorded by G-One from the call.
     */
    @GetMapping(value = "/api/deals/{id}/receipt", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DealReceipt> getReceiptJson(@PathVariable("id") String id) {
        return store.get(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * GET /api/deals/{id}/receipt.html: Bilingual Hindi/English "Sauda Parchi" HTML card.
     */
    @GetMapping(value = "/api/deals/{id}/receipt.html", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> getReceiptHtml(@PathVariable("id") String id) {
        return store.get(id)
                .map(r -> ResponseEntity.ok(SaudaParchiRenderer.renderHtml(r)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("<html><body><h3>404 Not Found</h3><p>No receipt found for deal ID " + id + "</p></body></html>"));
    }

    /**
     * POST /api/deals/{id}/sms or POST /api/deals/{id}/receipt/sms:
     * Queues a simulated SMS. Labelled "simulated, nothing sent".
     */
    @PostMapping(
            value = {"/api/deals/{id}/sms", "/api/deals/{id}/receipt/sms"},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<SimulatedSms> sendSimulatedSms(
            @PathVariable("id") String id,
            @RequestParam(value = "phone", required = false) String phoneParam,
            @RequestBody(required = false) Map<String, String> body
    ) {
        String phone = phoneParam;
        String message = null;
        if (body != null) {
            if (phone == null || phone.isBlank()) {
                phone = body.get("phone");
            }
            message = body.get("message");
        }
        if (phone == null || phone.isBlank()) {
            phone = "simulated-phone";
        }

        SimulatedSms sms = store.queueSms(id, phone, message);
        return ResponseEntity.ok(sms);
    }

    /**
     * GET /api/deals/{id}/sms:
     * Retrieves simulated SMS outbox messages for a specific deal.
     */
    @GetMapping(value = {"/api/deals/{id}/sms", "/api/deals/{id}/receipt/sms"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SimulatedSms> getDealSms(@PathVariable("id") String id) {
        return store.outboxForDeal(id);
    }

    /**
     * GET /api/sms/outbox:
     * Retrieves all simulated SMS messages. Labelled "simulated, nothing sent".
     */
    @GetMapping(value = "/api/sms/outbox", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SimulatedSms> getAllSms() {
        return store.allOutbox();
    }
}
