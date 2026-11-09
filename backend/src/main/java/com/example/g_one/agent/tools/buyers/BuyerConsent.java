package com.example.g_one.agent.tools.buyers;

import com.example.g_one.agent.deal.ReplyGuard;
import com.example.g_one.core.CallId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The connect-consent rule, enforced in code so the language model cannot skip it: {@code connect_buyer} works only for
 * a buyer that was suggested on an <em>earlier</em> turn of this call, and only while the farmer's latest turn is an
 * explicit yes. Unclear means no. Per-call state, dropped at hang-up by {@link #end}.
 */
@Component
public class BuyerConsent {
    private static final class Call {
        long turn;
        boolean yes;
        final Map<String, Long> suggestedAtTurn = new ConcurrentHashMap<>();
        BuyerConnector.ConnectRequest requested;
        String commodity;
        String quantity;
        String district;
    }

    private final Map<CallId, Call> calls = new ConcurrentHashMap<>();

    /** The farmer finished a turn. The brain calls this before the model runs. */
    public void heard(CallId callId, String farmerTurn) {
        Call c = call(callId);
        synchronized (c) {
            c.turn++;
            c.yes = ReplyGuard.isConsent(farmerTurn);
        }
    }

    /** These buyers were just suggested to the farmer, for this crop, quantity and district. */
    public void suggested(CallId callId, List<String> buyerIds, String commodity, String quantity, String district) {
        Call c = call(callId);
        synchronized (c) {
            // Keep the earliest turn: the model looks a buyer up again to get its id on the very turn the farmer says yes.
            for (String id : buyerIds) c.suggestedAtTurn.putIfAbsent(id, c.turn);
            c.commodity = commodity;
            c.quantity = quantity;
            c.district = district;
        }
    }

    /** True when {@code connect_buyer} may dial this buyer now. */
    public boolean allowed(CallId callId, String buyerId) {
        Call c = calls.get(callId);
        if (c == null) return false;
        synchronized (c) {
            Long at = c.suggestedAtTurn.get(buyerId);
            return c.yes && at != null && at < c.turn;
        }
    }

    /** True when this buyer was suggested on this call. */
    public boolean wasSuggested(CallId callId, String buyerId) {
        Call c = calls.get(callId);
        return c != null && c.suggestedAtTurn.containsKey(buyerId);
    }

    /** Records the connection and spends the yes: one yes never dials twice. */
    public BuyerConnector.ConnectRequest requested(CallId callId, Buyer buyer) {
        Call c = call(callId);
        synchronized (c) {
            c.yes = false;
            c.requested = new BuyerConnector.ConnectRequest(callId, buyer, c.commodity, c.quantity, c.district);
            return c.requested;
        }
    }

    /** The connection last requested on this call, if any. */
    public Optional<BuyerConnector.ConnectRequest> request(CallId callId) {
        Call c = calls.get(Objects.requireNonNull(callId, "callId"));
        if (c == null) return Optional.empty();
        synchronized (c) {
            return Optional.ofNullable(c.requested);
        }
    }

    /** The buyer ids suggested so far on this call. */
    public Set<String> suggested(CallId callId) {
        Call c = calls.get(callId);
        return c == null ? Set.of() : Set.copyOf(c.suggestedAtTurn.keySet());
    }

    public void end(CallId callId) {
        calls.remove(Objects.requireNonNull(callId, "callId"));
    }

    private Call call(CallId callId) {
        return calls.computeIfAbsent(Objects.requireNonNull(callId, "callId"), id -> new Call());
    }
}
