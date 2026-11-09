package com.example.g_one.receipt;

import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * In-memory thread-safe store for deal receipts fed by {@link ConsoleEvent.DealUpdated} on {@link ConsoleBus}.
 * Also maintains a simulated SMS outbox clearly labelled "simulated, nothing sent".
 */
@Service
public class ReceiptStore implements AutoCloseable {

    private final Map<String, DealReceipt> receipts = new ConcurrentHashMap<>();
    private final List<SimulatedSms> smsOutbox = new CopyOnWriteArrayList<>();
    private final ConsoleBus.Subscription subscription;

    public ReceiptStore() {
        this(null);
    }

    @Autowired
    public ReceiptStore(@Autowired(required = false) ConsoleBus bus) {
        if (bus != null) {
            this.subscription = bus.subscribe(this::onConsoleEvent);
        } else {
            this.subscription = null;
        }
        // Seed default verified deal receipt for Suresh Patel Onion Deal
        save(DealReceipt.of(
                "deal-suresh-onion-20261108",
                "agreed",
                "Onion",
                "Red Garva",
                "Lasalgaon APMC",
                "20 quintal",
                new java.math.BigDecimal("4300"),
                "quintal",
                "Monday morning at the farm, payment on delivery",
                true,
                true,
                System.currentTimeMillis()
        ));
    }

    private void onConsoleEvent(ConsoleEvent event) {
        if (event instanceof ConsoleEvent.DealUpdated deal) {
            saveFromEvent(deal);
        }
    }

    public void saveFromEvent(ConsoleEvent.DealUpdated deal) {
        String id = deal.callId() != null ? deal.callId().value() : UUID.randomUUID().toString();
        DealReceipt receipt = DealReceipt.of(
                id,
                deal.state() != null ? deal.state().wire() : "draft",
                deal.commodity(),
                deal.variety(),
                deal.market(),
                deal.quantity(),
                deal.price(),
                deal.unit(),
                deal.pickup(),
                deal.farmerYes(),
                deal.buyerYes(),
                deal.atMs()
        );
        save(receipt);
    }

    public void save(DealReceipt receipt) {
        if (receipt != null && receipt.id() != null) {
            receipts.put(receipt.id(), receipt);
        }
    }

    public Optional<DealReceipt> get(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String trimmed = id.trim();
        DealReceipt exact = receipts.get(trimmed);
        if (exact != null) return Optional.of(exact);

        // Try case-insensitive or without 'call-' prefix
        for (Map.Entry<String, DealReceipt> e : receipts.entrySet()) {
            if (e.getKey().equalsIgnoreCase(trimmed)
                    || e.getKey().replace("call-", "").equalsIgnoreCase(trimmed.replace("call-", ""))) {
                return Optional.of(e.getValue());
            }
        }
        if ("sample".equalsIgnoreCase(trimmed) || "latest".equalsIgnoreCase(trimmed)) {
            return receipts.values().stream().findFirst();
        }
        return Optional.empty();
    }

    public Collection<DealReceipt> all() {
        return Collections.unmodifiableCollection(receipts.values());
    }

    public SimulatedSms queueSms(String dealId, String phone, String customMessage) {
        String message;
        if (customMessage != null && !customMessage.isBlank()) {
            message = customMessage.trim();
        } else {
            Optional<DealReceipt> rOpt = get(dealId);
            if (rOpt.isPresent()) {
                DealReceipt r = rOpt.get();
                message = String.format(
                        "G-One Sauda Parchi: %s %s, Qty: %s, Price: Rs %s/%s. recorded by G-One from the call.",
                        r.commodity() != null ? r.commodity() : "Crop",
                        r.variety() != null ? r.variety() : "",
                        r.quantity() != null ? r.quantity() : "-",
                        r.price() != null ? r.price().toPlainString() : "-",
                        r.unit() != null ? r.unit() : "quintal"
                );
            } else {
                message = "G-One Sauda Parchi for deal " + dealId + ". recorded by G-One from the call.";
            }
        }

        SimulatedSms sms = SimulatedSms.of(
                UUID.randomUUID().toString(),
                dealId,
                phone != null ? phone.trim() : "unspecified",
                message,
                System.currentTimeMillis()
        );
        smsOutbox.add(sms);
        return sms;
    }

    public List<SimulatedSms> outboxForDeal(String dealId) {
        if (dealId == null) return List.of();
        List<SimulatedSms> list = new ArrayList<>();
        for (SimulatedSms s : smsOutbox) {
            if (s.dealId().equalsIgnoreCase(dealId)
                    || s.dealId().replace("call-", "").equalsIgnoreCase(dealId.replace("call-", ""))) {
                list.add(s);
            }
        }
        return Collections.unmodifiableList(list);
    }

    public List<SimulatedSms> allOutbox() {
        return Collections.unmodifiableList(smsOutbox);
    }

    public void clear() {
        receipts.clear();
        smsOutbox.clear();
    }

    @Override
    @PreDestroy
    public void close() {
        if (subscription != null) {
            subscription.close();
        }
    }
}
