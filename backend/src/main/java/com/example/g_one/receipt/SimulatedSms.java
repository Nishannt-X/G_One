package com.example.g_one.receipt;

/**
 * A simulated SMS message for an agreed deal receipt. Labelled "simulated, nothing sent".
 */
public record SimulatedSms(
        String id,
        String dealId,
        String recipientPhone,
        String messageText,
        long timestampMs,
        String status,
        String note
) {
    public static final String LABEL = "simulated, nothing sent";

    public static SimulatedSms of(String id, String dealId, String recipientPhone, String messageText, long timestampMs) {
        return new SimulatedSms(id, dealId, recipientPhone, messageText, timestampMs, LABEL, LABEL);
    }
}
