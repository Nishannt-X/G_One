package com.example.g_one.agent.mediation;

import com.example.g_one.agent.deal.DealDesk;
import com.example.g_one.agent.deal.Offer;
import com.example.g_one.agent.llm.SpokenNumbers;
import com.example.g_one.core.LegRole;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only facts the assistant may use to answer a party who speaks to it directly, built per asker from a whitelist. The
 * buyer's view has no parameter that could carry a whisper, the market reference, the farmer's memory, the intake
 * conversation, a caller key or a phone number, so none of those can reach the model on a buyer's question. Its lines
 * are the buyer's own leg ({@link CallLedger}): what the buyer said and heard.
 */
final class AskDigest {
    /** The buyer as the farmer may hear about them: directory name, kind of business and district. Never a phone. */
    record Buyer(String name, String type, String district) {}

    /** The dated public market reference: the farmer's view only. */
    record Reference(String source, String market, String crop, String variety, BigDecimal price, String unit,
                     LocalDate date) {}

    private static final Map<String, Integer> KG = Map.of("kg", 1, "kilo", 1, "kilogram", 1, "quintal", 100,
            "ton", 1000, "tonne", 1000);
    private static final Pattern AMOUNT = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*([\\p{L}]+)");

    private AskDigest() {}

    /** What the buyer may be told: the deal so far and their own leg of the call. */
    static ObjectNode buyerView(DealDesk.Card card, String crop, String farmerName,
                                List<CallLedger.Line> buyerLeg) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("you", "buyer");
        ObjectNode other = root.putObject("otherParty");
        other.put("role", "farmer");
        if (farmerName != null) other.put("name", farmerName);
        deal(root, card, crop);
        lines(root, buyerLeg, LegRole.FARMER);
        return root;
    }

    /** What the farmer may be told: also who the buyer is and the dated market reference. */
    static ObjectNode farmerView(DealDesk.Card card, String crop, Buyer buyer, Reference reference,
                                 List<CallLedger.Line> farmerLeg, String code) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("you", "farmer");
        ObjectNode other = root.putObject("otherParty");
        other.put("role", "buyer");
        if (buyer != null) {
            if (buyer.name() != null) other.put("name", buyer.name());
            if (buyer.type() != null) other.put("type", buyer.type());
            if (buyer.district() != null) other.put("district", buyer.district());
        }
        deal(root, card, crop);
        if (reference != null) {
            ObjectNode r = root.putObject("marketReference");
            r.put("source", reference.source());
            r.put("market", reference.market());
            r.put("crop", reference.crop());
            if (reference.variety() != null) r.put("variety", reference.variety());
            r.put("price", plain(reference.price()));
            r.put("unit", reference.unit());
            r.put("date", reference.date().toString());
            r.put("dateSpoken", SpokenNumbers.date(reference.date(), code));
            r.put("note", "a dated public reference, not today's rate");
            BigDecimal offer = perQuintal(card);
            if (offer != null && "quintal".equals(Offer.unit(reference.unit()))) {
                r.put("offerMinusReferenceRupeesPerQuintal", plain(offer.subtract(reference.price())));
            }
        }
        lines(root, farmerLeg, LegRole.BUYER);
        return root;
    }

    private static void deal(ObjectNode root, DealDesk.Card card, String crop) {
        ObjectNode d = root.putObject("deal");
        d.put("state", card.state());
        String commodity = card.commodity() != null ? card.commodity() : crop;
        if (commodity != null) d.put("crop", commodity);
        if (card.variety() != null) d.put("variety", card.variety());
        if (card.market() != null) d.put("market", card.market());
        if (card.hasPrice()) {
            d.put("priceRupees", plain(card.price()));
            d.put("perUnit", card.unit());
            BigDecimal kg = perKg(card);
            if (kg != null && !"kg".equals(Offer.unit(card.unit()))) d.put("pricePerKgRupees", plain(kg));
        }
        if (card.quantity() != null) d.put("quantity", card.quantity());
        if (card.pickup() != null) d.put("pickup", card.pickup());
        if (card.payment() != null) d.put("payment", card.payment());
        BigDecimal value = estimatedValue(card);
        if (value != null) d.put("estimatedValueRupees", plain(value));
        d.put("farmerConfirmed", card.farmerYes());
        d.put("buyerConfirmed", card.buyerYes());
        ArrayNode missing = root.putArray("notYetAgreed");
        missing(card).forEach(missing::add);
    }

    /** What the deal still lacks, in plain English words the model turns into the asker's language. */
    static List<String> missing(DealDesk.Card card) {
        List<String> out = new ArrayList<>();
        if (!card.hasPrice()) out.add("price");
        if (card.quantity() == null) out.add("quantity");
        if (card.pickup() == null) out.add("pickup time and place");
        if (card.payment() == null) out.add("payment terms");
        if (card.hasPrice()) {
            switch (card.state()) {
                case "offer_open" -> out.add("the farmer accepting the buyer's price");
                case "readback", "confirming" -> {
                    if (!card.farmerYes()) out.add("the farmer's yes to the read-back summary");
                    if (!card.buyerYes()) out.add("the buyer's yes to the read-back summary");
                }
                case "none" -> out.add("a fresh price: the last offer was closed");
                default -> { }
            }
        }
        return out;
    }

    /** Price times quantity in rupees, when both are stated in units that convert; otherwise null. */
    static BigDecimal estimatedValue(DealDesk.Card card) {
        if (!card.hasPrice() || card.unit() == null || card.quantity() == null) return null;
        Matcher m = AMOUNT.matcher(card.quantity());
        if (!m.find()) return null;
        Integer quantityKg = KG.get(Offer.unit(m.group(2).toLowerCase(Locale.ROOT)));
        Integer priceKg = KG.get(Offer.unit(card.unit()));
        if (quantityKg == null || priceKg == null) return null;
        BigDecimal amount = new BigDecimal(m.group(1));
        return amount.multiply(BigDecimal.valueOf(quantityKg)).multiply(card.price())
                .divide(BigDecimal.valueOf(priceKg), 0, RoundingMode.HALF_UP);
    }

    /** The price per kilo, for a party who thinks in kilos; null when the unit does not convert. */
    static BigDecimal perKg(DealDesk.Card card) {
        Integer kg = card.unit() == null ? null : KG.get(Offer.unit(card.unit()));
        if (!card.hasPrice() || kg == null) return null;
        return card.price().divide(BigDecimal.valueOf(kg), 2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /** "4000", never "4E+3": the model and the number check read the figure as written. */
    static BigDecimal plain(BigDecimal v) {
        BigDecimal t = v.stripTrailingZeros();
        return t.scale() < 0 ? t.setScale(0) : t;
    }

    private static BigDecimal perQuintal(DealDesk.Card card) {
        BigDecimal kg = perKg(card);
        return kg == null ? null : kg.multiply(BigDecimal.valueOf(100)).stripTrailingZeros();
    }

    private static void lines(ObjectNode root, List<CallLedger.Line> leg, LegRole other) {
        ArrayNode out = root.putArray("lines");
        String otherName = other.wire();
        for (CallLedger.Line line : leg) {
            ObjectNode n = out.addObject();
            n.put("who", switch (line.kind()) {
                case SAID -> "you";
                case HEARD -> otherName + " (as translated for you)";
                case ASSISTANT -> "G-One to you";
                case WHISPER -> "G-One, privately to you";
                case ASKED -> "you, to G-One only";
                case ANSWERED -> "G-One's answer, to you only";
            });
            n.put("text", line.text());
        }
    }
}
