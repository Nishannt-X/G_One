package com.example.g_one.receipt;

import java.math.BigDecimal;

/**
 * Structured record of an agreed or drafted deal recorded by G-One during mediation.
 */
public record DealReceipt(
        String id,
        String state,
        String commodity,
        String variety,
        String market,
        String quantity,
        BigDecimal price,
        String unit,
        String pickup,
        boolean farmerYes,
        boolean buyerYes,
        long atMs,
        String wording,
        String note
) {
    public static final String STANDARD_WORDING = "recorded by G-One from the call";
    public static final String STANDARD_NOTE = "Nonbinding negotiation summary recorded by G-One from the call. Indicative sample benchmarks.";

    public static DealReceipt of(
            String id,
            String state,
            String commodity,
            String variety,
            String market,
            String quantity,
            BigDecimal price,
            String unit,
            String pickup,
            boolean farmerYes,
            boolean buyerYes,
            long atMs
    ) {
        return new DealReceipt(
                id,
                state,
                commodity,
                variety,
                market,
                quantity,
                price,
                unit,
                pickup,
                farmerYes,
                buyerYes,
                atMs,
                STANDARD_WORDING,
                STANDARD_NOTE
        );
    }
}
