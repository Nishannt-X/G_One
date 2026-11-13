package com.example.g_one.agent.deal;

import com.example.g_one.agent.deal.Agreement.Ask;
import com.example.g_one.agent.deal.Agreement.Intent;
import com.example.g_one.agent.deal.Agreement.Kind;
import com.example.g_one.agent.deal.Agreement.Notice;
import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.ConsoleEvent.DealState;
import com.example.g_one.core.LegRole;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One call's deal: feeds the {@link Agreement} state machine from mediation turns, publishes {@code deal_updated}, and
 * tells the {@link Output} what to say. Only an explicit spoken yes from each side, after both heard the read-back of
 * these exact terms, makes a deal summary. Nonbinding.
 */
public final class DealDesk {
    public static final Duration CONFIRM_WINDOW = Duration.ofSeconds(120);
    public static final Duration OFFER_TTL = Duration.ofMinutes(10);

    /** What a reply meant, after the code guard. */
    public enum Heard { YES, NO, UNCLEAR, CHANGED, NONE }

    /** What the deal is waiting to hear from someone. */
    public enum Awaiting { ACCEPT, CONFIRM }

    /** What the deal asks mediation to do. */
    public interface Output {
        /** Does slow preparation for a read-back of these terms. Called before the state machine's lock is taken. */
        default void prepare(Terms terms) {}

        /** Speaks a read-back, protected from barge-in. {@code played} gets true only when it was heard to the end. */
        void readback(LegRole role, Terms terms, Consumer<Boolean> played);

        /** Speaks a fixed line by key to one side. */
        void say(LegRole role, String lineKey);

        /** Both sides said yes. Mediation ends the call. */
        void agreed(Terms terms);

        /** Runs {@code tick} after {@code delay}. */
        void after(Duration delay, Runnable tick);
    }

    private final CallId callId;
    private final Clock clock;
    private final ConsoleBus bus;
    private final Output out;
    private final Agreement agreement;
    private final Set<LegRole> confirmed = EnumSet.noneOf(LegRole.class);
    private final AtomicReference<Terms> agreed = new AtomicReference<>();
    private String quantity;
    private String pickup;
    private String payment;

    /** @param quantity the amount the farmer gave at intake, used until someone states another; may be null */
    public DealDesk(CallId callId, Clock clock, ConsoleBus bus, Output out, String quantity) {
        this.callId = callId;
        this.clock = clock;
        this.bus = bus;
        this.out = out;
        this.quantity = Terms.phrase(quantity);
        this.agreement = new Agreement(clock, CONFIRM_WINDOW, OFFER_TTL);
    }

    /** What a turn from {@code role} would be answering right now, if anything. */
    public Optional<Awaiting> awaiting(LegRole role) {
        Ask ask = agreement.ask(role);
        if (ask == null) return Optional.empty();
        return Optional.of(ask.phase() == Agreement.Phase.ACCEPT ? Awaiting.ACCEPT : Awaiting.CONFIRM);
    }

    /**
     * The deal as it stands, for answering a question about it: only what was said aloud on the call. {@code state} is
     * "none", "offer_open", "readback", "confirming" or "agreed"; price, unit, crop, variety and market are null until a
     * buyer states a clear price. Quantity, pickup and payment are the latest stated (quantity starts as the amount the
     * buyer was told in the introduction).
     */
    public record Card(String state, String commodity, String variety, String market, BigDecimal price, String unit,
                       String quantity, String pickup, String payment, boolean farmerYes, boolean buyerYes) {
        public boolean hasPrice() {
            return price != null;
        }
    }

    public Card card() {
        Agreement.View v = agreement.view();
        // The agreed terms only while the agreement itself says agreed: a later offer replaces them.
        boolean isAgreed = v.state() == Agreement.State.AGREED;
        Terms t = isAgreed && agreed.get() != null ? agreed.get() : v.terms();
        String q;
        String p;
        String pay;
        synchronized (this) {
            q = quantity;
            p = pickup;
            pay = payment;
        }
        String state = switch (v.state()) {
            case IDLE -> "none";
            case OFFER_OPEN -> "offer_open";
            case READBACK -> "readback";
            case CONFIRMING -> "confirming";
            case AGREED -> "agreed";
        };
        if (t == null) return new Card(state, null, null, null, null, null, q, p, pay, false, false);
        return new Card(state, t.commodity(), t.variety(), t.market(), t.price(), t.unit(),
                firstNonNull(t.quantity(), q), firstNonNull(t.pickup(), p), firstNonNull(t.payment(), pay),
                isAgreed || v.farmerYes(), isAgreed || v.buyerYes());
    }

    /** What a turn stated about the sale. Only a clear buyer offer opens or replaces a draft. */
    public void terms(Offer offer, LegRole speaker, long seq) {
        terms(offer, speaker, seq, false);
    }

    /** Total basis must be verified from the buyer's own words before this is called. */
    public void verifiedTotal(Offer offer, long seq) {
        terms(offer, LegRole.BUYER, seq, true);
    }

    /** A newer offer with an unknown basis invalidates an older draft. */
    public void unpricedOffer() {
        agreed.set(null);
        handle(agreement.superseded());
    }

    private void terms(Offer offer, LegRole speaker, long seq, boolean verifiedTotal) {
        if (offer == null) return;
        synchronized (this) {
            if (offer.quantity() != null) quantity = firstNonNull(Terms.phrase(offer.quantity()), quantity);
            if (offer.pickup() != null) pickup = firstNonNull(Terms.phrase(offer.pickup()), pickup);
            if (offer.payment() != null) payment = firstNonNull(Terms.phrase(offer.payment()), payment);
        }
        Terms open = current();
        if (speaker == LegRole.BUYER) {
            Terms clear = verifiedTotal ? Terms.fromVerifiedTotal(offer) : Terms.from(offer);
            if (clear != null) {
                handle(agreement.offer(details(clear), seq));
                return;
            }
        }
        if (offer.hasDetails() && open != null && agreement.state() == Agreement.State.OFFER_OPEN) {
            handle(agreement.offer(open.withDetails(Terms.phrase(offer.quantity()), Terms.phrase(offer.pickup()),
                    Terms.phrase(offer.payment())), seq));
        }
    }

    private synchronized Terms details(Terms t) {
        return t.orDetails(quantity, pickup, payment);
    }

    private Terms current() {
        Ask ask = agreement.ask(LegRole.FARMER);
        return ask == null ? null : ask.terms();
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /** A reply from {@code role}. */
    public void heard(LegRole role, Heard heard, long seq, long spokenAtMs) {
        Ask ask = agreement.ask(role);
        if (ask == null) {
            tick();
            return;
        }
        boolean confirming = ask.phase() == Agreement.Phase.CONFIRM;
        List<Notice> notices = agreement.heard(ask.version(), role, intent(heard), seq, Instant.ofEpochMilli(spokenAtMs));
        handle(notices);
        if (confirming && heard == Heard.YES && notices.isEmpty() && !isConfirmed(role)) {
            out.say(role, "confirmRetry"); // spoken over the read-back, so it could not count: never leave them waiting
        }
    }

    private synchronized boolean isConfirmed(LegRole role) {
        return confirmed.contains(role);
    }

    private static Intent intent(Heard h) {
        return switch (h) {
            case YES -> Intent.YES;
            case NO -> Intent.NO;
            case UNCLEAR -> Intent.UNCLEAR;
            case CHANGED -> Intent.CHANGED;
            case NONE -> Intent.NONE;
        };
    }

    /** Applies the time limits. Safe at any moment. */
    public void tick() {
        handle(agreement.expire());
    }

    /** The terms both sides confirmed, once they have. */
    public Optional<Terms> agreed() {
        return Optional.ofNullable(agreed.get());
    }

    private void handle(List<Notice> notices) {
        for (Notice n : notices) handle(n);
    }

    private void handle(Notice n) {
        switch (n.kind()) {
            case OPENED, READBACK_FAILED -> publish(DealState.DRAFT, n);
            case READBACK -> readback(n);
            case READBACK_PLAYED -> publish(DealState.READBACK, n);
            case CONFIRMING -> publish(DealState.CONFIRMING, n);
            case CONFIRMED -> {
                synchronized (this) {
                    confirmed.add(n.role());
                }
                publish(DealState.CONFIRMING, n);
                out.say(n.role(), "confirmedWait");
            }
            case RETRY -> out.say(n.role(), "confirmRetry");
            case AGREED -> {
                agreed.set(n.terms());
                publish(DealState.AGREED, n);
                out.agreed(n.terms());
            }
            case DECLINED -> closed(n, "declined");
            case EXPIRED -> closed(n, "expired");
            case VOIDED -> closed(n, "voided");
            case REJECTED, LAPSED -> publish(DealState.CLOSED, n);
        }
    }

    private void readback(Notice n) {
        publish(DealState.READBACK, n);
        int draft = n.version();
        out.after(CONFIRM_WINDOW.plusSeconds(1), this::tick);
        out.prepare(n.terms());
        boolean sent = agreement.sendReadback(draft, () -> {
            out.readback(LegRole.FARMER, n.terms(), ok -> played(draft, LegRole.FARMER, ok));
            out.readback(LegRole.BUYER, n.terms(), ok -> played(draft, LegRole.BUYER, ok));
        });
        if (!sent) handle(agreement.readbackFailed(draft));
    }

    private void played(int draft, LegRole role, boolean ok) {
        handle(ok ? agreement.readbackPlayed(draft, role) : agreement.readbackFailed(draft));
    }

    private void closed(Notice n, String lineKey) {
        synchronized (this) {
            confirmed.clear();
        }
        publish(DealState.CLOSED, n);
        out.say(LegRole.FARMER, lineKey);
        out.say(LegRole.BUYER, lineKey);
    }

    private void publish(DealState state, Notice n) {
        Terms t = n.terms();
        if (t == null) return;
        bus.publish(new ConsoleEvent.DealUpdated(callId, clock.millis(), state, t.commodity(), t.variety(), t.market(),
                t.quantity(), t.price(), t.unit(), pickupAndPayment(t), n.farmerYes(), n.buyerYes()));
    }

    /** "on delivery", also when the model wrote "pay on delivery", so it reads right after the word "payment". */
    private static String payment(Terms t) {
        return t.payment().replaceFirst("(?i)^pay(ment)?\\s+", "");
    }

    /** The console's pickup field: "Monday at the farm; payment on delivery". */
    private static String pickupAndPayment(Terms t) {
        if (t.payment() == null) return t.pickup();
        return (t.pickup() == null ? "" : t.pickup() + "; ") + "payment " + payment(t);
    }

    /** The confirmed terms in one English sentence, for the call gist. */
    public static String summary(Terms t, String buyerName) {
        return "Deal summary confirmed by voice with " + buyerName + ": " + (t.quantity() == null ? "" : t.quantity() + " ")
                + t.commodity() + " at " + t.priceText() + ("total".equals(t.unit()) ? " rupees total" : " rupees per " + t.unit())
                + (t.pickup() == null ? "" : ", pickup " + t.pickup())
                + (t.payment() == null ? "" : ", payment " + payment(t)) + ". Not a contract.";
    }
}
