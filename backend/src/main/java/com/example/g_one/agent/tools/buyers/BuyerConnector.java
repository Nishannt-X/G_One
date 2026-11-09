package com.example.g_one.agent.tools.buyers;

import com.example.g_one.core.CallId;
import java.util.Objects;

/**
 * Starts a buyer leg for a call. Telephony implements it by dialling {@code buyer.phoneRef()}; when no implementation
 * is wired, the browser call simulator is the way a buyer joins. The resulting leg arrives through the
 * {@code LegRegistry} with role BUYER.
 */
@FunctionalInterface
public interface BuyerConnector {

    /**
     * Asks for the buyer leg and returns quickly; the leg joins later, or never (no answer).
     *
     * @throws RuntimeException when the leg cannot be requested at all
     */
    void connect(ConnectRequest request);

    /**
     * Who to connect and what they are being called about, as the farmer said it.
     *
     * @param commodity the crop, or null
     * @param quantity the amount as said, or null
     * @param district the farmer's district, or null
     */
    record ConnectRequest(CallId callId, Buyer buyer, String commodity, String quantity, String district) {
        public ConnectRequest {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(buyer, "buyer");
        }
    }
}
