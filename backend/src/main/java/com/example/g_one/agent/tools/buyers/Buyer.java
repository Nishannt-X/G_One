package com.example.g_one.agent.tools.buyers;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One synthetic entry in the buyer directory. {@code phoneRef} is a key into {@code G_ONE_BUYER_PHONES}: telephony
 * resolves it to the real demo number, and it never reaches the model, the console or the browser.
 *
 * @param district where the buyer is based
 * @param serves the districts the buyer collects from
 * @param language the Gnani locale code the buyer is spoken to in
 * @param reliability 0 to 100, how the directory rates this buyer
 */
public record Buyer(String id, String name, String type, String district, List<String> serves, String language,
                    int reliability, String phoneRef, List<Crop> crops) {

    public Buyer {
        serves = serves == null ? List.of() : List.copyOf(serves);
        crops = crops == null ? List.of() : List.copyOf(crops);
    }

    /** What the buyer wants of one crop. Prices are the directory's indicative range, in rupees per {@code unit}. */
    public record Crop(String crop, int minPrice, int maxPrice, String unit, int minQuantity, int maxQuantity) {}

    /** The buyer's entry for this crop name (AGMARKNET spelling, any case). */
    public Optional<Crop> crop(String name) {
        return crops.stream().filter(c -> c.crop().equalsIgnoreCase(name)).findFirst();
    }

    /** True when the buyer collects from this district (display name, any case). */
    public boolean serves(String districtName) {
        return serves.stream().anyMatch(d -> d.toLowerCase(Locale.ROOT).equals(districtName.toLowerCase(Locale.ROOT)));
    }
}
