package com.example.g_one.agent.tools.buyers;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The buyer directory for the web console. {@code GET /api/buyers} lists every buyer; with {@code commodity} (and
 * optional {@code district} and {@code quantity}) it returns the buyers for that crop, best match first, in the same
 * order the assistant uses. Synthetic data; no response ever carries a phone number or {@code phoneRef}.
 */
@RestController
@RequestMapping("/api/buyers")
class BuyersController {
    private final BuyerDirectory directory;
    private final BuyerRanker ranker;

    BuyersController(BuyerDirectory directory, BuyerRanker ranker) {
        this.directory = directory;
        this.ranker = ranker;
    }

    /** One directory entry as the console sees it. {@code score} and {@code distanceKm} are null when not ranked. */
    record BuyerView(String id, String name, String type, String district, List<String> serves, String language,
                     int reliability, List<CropView> crops, Double score, Long distanceKm, Boolean servesDistrict) {}

    record CropView(String crop, int minPrice, int maxPrice, String unit, int minQuantity, int maxQuantity) {}

    record BuyersResponse(List<BuyerView> buyers) {}

    @GetMapping
    BuyersResponse buyers(@RequestParam(required = false) String commodity,
                          @RequestParam(required = false) String district,
                          @RequestParam(required = false) Double quantity) {
        if (commodity == null || commodity.isBlank()) {
            return new BuyersResponse(directory.all().stream().map(b -> view(b, null, null, null)).toList());
        }
        return new BuyersResponse(ranker.rank(commodity, quantity, district).stream()
                .map(m -> view(m.buyer(), m.score(), m.distanceKm(), m.servesDistrict()))
                .toList());
    }

    private static BuyerView view(Buyer b, Double score, Long km, Boolean serves) {
        return new BuyerView(b.id(), b.name(), b.type(), b.district(), b.serves(), b.language(), b.reliability(),
                b.crops().stream().map(c -> new CropView(c.crop(), c.minPrice(), c.maxPrice(), c.unit(), c.minQuantity(),
                        c.maxQuantity())).toList(),
                score == null ? null : Math.round(score * 1000) / 1000.0, km, serves);
    }
}
