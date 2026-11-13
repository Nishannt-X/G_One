package com.example.g_one.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** What a stored deal card's checked terms add up to, worked out like the console's. Nothing is guessed. */
final class DealTotal {
    private DealTotal() {}

    /**
     * The total in rupees: the price itself for a "total" price; quantity times price when both are in the same counted
     * unit or both are weights; otherwise null.
     */
    static BigDecimal of(DealCard.Quantity quantity, BigDecimal price, String priceUnit) {
        if (price == null || priceUnit == null) return null;
        if ("total".equals(priceUnit)) return plain(price);
        if (quantity == null) return null;
        DealCard.Unit unit = DealCard.unit(priceUnit).orElse(null);
        if (unit == null) return null;
        if (unit == quantity.unit()) return plain(quantity.value().multiply(price));
        if (unit.kg == null || quantity.unit().kg == null) return null;
        BigDecimal kg = quantity.value().multiply(quantity.unit().kg);
        return plain(kg.multiply(price).divide(unit.kg, 2, RoundingMode.HALF_UP));
    }

    /** 21000 rather than 2.1E+4, and no trailing zeros. */
    static BigDecimal plain(BigDecimal v) {
        return new BigDecimal(v.stripTrailingZeros().toPlainString());
    }
}
