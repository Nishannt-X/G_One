package com.example.g_one.agent.tools.buyers;

import com.example.g_one.agent.tools.mandi.MandiCatalog;
import com.example.g_one.agent.tools.weather.District;
import com.example.g_one.agent.tools.weather.DistrictTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Ranks the directory for one request. Only buyers who want the crop qualify. The rest is a weighted score, so plain
 * code decides the order and the same request always gives the same order:
 * distance 30 percent (a buyer who collects from the farmer's district gets a bonus), the directory's indicative price
 * 30 percent, reliability 25 percent, quantity fit 15 percent.
 */
@Component
public class BuyerRanker {
    static final double FAR_KM = 800;
    private static final double EARTH_KM = 6371;

    private final BuyerDirectory directory;
    private final MandiCatalog catalog;
    private final DistrictTable districts;

    public BuyerRanker(BuyerDirectory directory, MandiCatalog catalog, DistrictTable districts) {
        this.directory = directory;
        this.catalog = catalog;
        this.districts = districts;
    }

    /**
     * One ranked buyer.
     *
     * @param servesDistrict the buyer collects from the farmer's district
     * @param distanceKm from the farmer's district to the buyer's base; null when either is unknown
     * @param score 0 to 1, higher is better
     */
    public record Match(Buyer buyer, Buyer.Crop crop, boolean servesDistrict, Long distanceKm, double score) {}

    /**
     * @param commodity the crop as said (any alias the mandi catalog knows)
     * @param quantity quintals, or null when not said
     * @param district the farmer's district as said, or null
     * @return every buyer who wants the crop, best first
     */
    public List<Match> rank(String commodity, Double quantity, String district) {
        String crop = commodity == null ? "" : catalog.commodity(commodity).orElse(commodity.strip());
        Optional<District> from = districts.find(district);
        List<Buyer> wanting = directory.all().stream().filter(b -> b.crop(crop).isPresent()).toList();
        if (wanting.isEmpty()) return List.of();

        int lowest = wanting.stream().mapToInt(b -> mid(b.crop(crop).orElseThrow())).min().orElseThrow();
        int highest = wanting.stream().mapToInt(b -> mid(b.crop(crop).orElseThrow())).max().orElseThrow();

        List<Match> out = new ArrayList<>();
        for (Buyer b : wanting) {
            Buyer.Crop c = b.crop(crop).orElseThrow();
            boolean serves = from.isPresent() && b.serves(from.get().name());
            Long km = from.flatMap(f -> districts.find(b.district()).map(d -> Math.round(distanceKm(f, d)))).orElse(null);
            double near = km == null ? 0.5 : Math.max(0, 1 - km / FAR_KM);
            double distance = Math.min(1, near + (serves ? 0.25 : 0));
            double price = highest == lowest ? 1 : (mid(c) - lowest) / (double) (highest - lowest);
            double score = 0.30 * distance + 0.30 * price + 0.25 * b.reliability() / 100.0 + 0.15 * fit(c, quantity);
            out.add(new Match(b, c, serves, km, score));
        }
        out.sort(Comparator.comparingDouble(Match::score).reversed().thenComparing(m -> m.buyer().id()));
        return List.copyOf(out);
    }

    private static int mid(Buyer.Crop c) {
        return (c.minPrice() + c.maxPrice()) / 2;
    }

    private static double fit(Buyer.Crop c, Double quantity) {
        if (quantity == null) return 0.7;
        if (quantity < c.minQuantity()) return 0.2;
        if (quantity > c.maxQuantity()) return 0.5;
        return 1;
    }

    static double distanceKm(District a, District b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_KM * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
