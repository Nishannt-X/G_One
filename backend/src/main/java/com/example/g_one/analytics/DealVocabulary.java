package com.example.g_one.analytics;

import com.example.g_one.agent.deal.Offer;
import com.example.g_one.agent.tools.buyers.Buyer;
import com.example.g_one.agent.tools.buyers.BuyerDirectory;
import com.example.g_one.agent.tools.mandi.MandiCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Optional;

/**
 * The closed lists a stored deal card may name: crops and markets from the mandi catalog, buyers from the synthetic
 * buyer directory. Anything else is stored as "other" (crop, market) or not at all (buyer), never as spoken.
 */
public final class DealVocabulary {
    static final String OTHER = "other";

    private final MandiCatalog catalog;
    private final BuyerDirectory buyers;

    public DealVocabulary(MandiCatalog catalog, BuyerDirectory buyers) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.buyers = Objects.requireNonNull(buyers, "buyers");
    }

    /** The bundled catalog and directory, as the application loads them. */
    static DealVocabulary bundled(ObjectMapper json) {
        return new DealVocabulary(new MandiCatalog(json), new BuyerDirectory(json));
    }

    /** The catalog's crop name, "other" for an unknown crop, or null when none was stated or the text is unsafe. */
    String crop(String spoken) {
        if (absentOrUnsafe(spoken)) return null;
        return catalog.commodity(spoken.strip()).orElse(OTHER);
    }

    /** The catalog's market name, "other" for an unknown market, or null when none was stated or the text is unsafe. */
    String market(String spoken) {
        if (absentOrUnsafe(spoken)) return null;
        Optional<String> direct = catalog.market(spoken.strip());
        if (direct.isPresent()) return direct.get();
        // "Burdwan mandi" is "Burdwan APMC": compare without the generic words.
        String key = Offer.marketKey(spoken);
        if (key != null && !key.isEmpty()) {
            for (String m : catalog.markets()) {
                if (key.equals(Offer.marketKey(m))) return m;
            }
        }
        return OTHER;
    }

    /** The directory buyer with this id, if the id is a directory id. */
    Optional<Buyer> buyer(String id) {
        if (id == null || id.length() > 64 || DealCard.unsafe(id)) return Optional.empty();
        return buyers.find(id);
    }

    private static boolean absentOrUnsafe(String s) {
        return s == null || s.isBlank() || DealCard.unsafe(s);
    }
}
