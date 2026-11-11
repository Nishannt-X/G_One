package com.example.g_one.agent.mediation;

import com.example.g_one.agent.deal.DealDesk;
import com.example.g_one.agent.deal.Offer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The typed facts an answer to a direct question may state, built per asker from a whitelist and read only by code: no
 * model ever sees them, and an answer is a fixed line with these values filled in ({@link AskLines}). The buyer's facts
 * have no field that could carry a whisper, the market reference, the farmer's memory, the intake conversation, a caller
 * key or a phone number, and their terms are the buyer's own {@link CallLedger.Known}: what the buyer said or heard to the
 * end. Figures worked out here (total value, price per kilo) are spoken as G-One's own calculation, never as said.
 */
final class AskDigest {
    /** Where the last price stands for this asker: none heard, still open, agreed, or heard but no longer open. */
    enum PriceState { NONE, OPEN, AGREED, CLOSED }

    /** The dated public market reference: the farmer's facts only. */
    record Reference(String source, String market, String crop, String variety, BigDecimal price, String unit,
                     LocalDate date) {}

    /**
     * @param otherName the other party as the asker may hear them named: the directory buyer name for the farmer, the
     *                  farmer's name from the introduction (or null) for the buyer
     * @param state the deal desk's state: "none", "offer_open", "readback", "confirming" or "agreed"
     * @param lastHeard the last translation that played to the end on the asker's leg, or null
     * @param reference null for the buyer, always
     */
    record Facts(String otherName, String crop, String variety, String quantity, BigDecimal price, String unit,
                 PriceState priceState, String pickup, String payment, String state, String lastHeard,
                 Reference reference) {

        /** Price times quantity in rupees, when both are known in units that convert; otherwise null. */
        BigDecimal value() {
            if (price == null || unit == null || quantity == null) return null;
            Matcher m = AMOUNT.matcher(quantity);
            if (!m.find()) return null;
            Integer quantityKg = KG.get(Offer.unit(m.group(2)));
            Integer priceKg = KG.get(Offer.unit(unit));
            if (quantityKg == null || priceKg == null) return null;
            return new BigDecimal(m.group(1)).multiply(BigDecimal.valueOf(quantityKg)).multiply(price)
                    .divide(BigDecimal.valueOf(priceKg), 0, RoundingMode.HALF_UP);
        }

        /** The price per kilo, or null when the unit does not convert or already is a kilo. */
        BigDecimal perKg() {
            Integer kg = unit == null ? null : KG.get(Offer.unit(unit));
            if (price == null || kg == null || kg == 1) return null;
            return price.divide(BigDecimal.valueOf(kg), 2, RoundingMode.HALF_UP).stripTrailingZeros();
        }
    }

    private static final Map<String, Integer> KG = Map.of("kg", 1, "kilo", 1, "kilogram", 1, "quintal", 100,
            "ton", 1000, "tonne", 1000);
    private static final Pattern AMOUNT = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*([\\p{L}]+)");

    private AskDigest() {}

    /** What the buyer may be told: the terms the buyer said or heard, the deal's state, and their own leg. */
    static Facts forBuyer(CallLedger.Known buyerKnows, DealDesk.Card desk, String farmerName, String lastHeard) {
        return facts(buyerKnows, desk, farmerName, lastHeard, null);
    }

    /** What the farmer may be told: also the buyer's directory name and the dated market reference. */
    static Facts forFarmer(CallLedger.Known farmerKnows, DealDesk.Card desk, String buyerName, String lastHeard,
                           Reference reference) {
        return facts(farmerKnows, desk, buyerName, lastHeard, reference);
    }

    private static Facts facts(CallLedger.Known k, DealDesk.Card desk, String otherName, String lastHeard,
                               Reference reference) {
        return new Facts(otherName, k.commodity(), k.variety(), k.quantity(), k.price(), k.unit(), priceState(k, desk),
                k.pickup(), k.payment(), desk.state(), lastHeard, reference);
    }

    /**
     * The asker's last known price against the desk: open or agreed only when the desk holds that same price now, so an
     * expired, declined or replaced offer reads as "no longer open", never as "no price offered".
     */
    static PriceState priceState(CallLedger.Known k, DealDesk.Card desk) {
        if (k.price() == null) return PriceState.NONE;
        boolean same = desk.hasPrice() && desk.price().compareTo(k.price()) == 0;
        if (same && "agreed".equals(desk.state())) return PriceState.AGREED;
        if (same && !"none".equals(desk.state())) return PriceState.OPEN;
        return PriceState.CLOSED;
    }
}
