package com.example.g_one.agent.tools.buyers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** The synthetic buyer directory, read once from {@code /buyers.json}. A bad entry fails at startup, not on a call. */
@Component
public class BuyerDirectory {
    public static final String RESOURCE = "/buyers.json";
    /** phoneRef is a key name, so it can never be mistaken for (or be) a number. */
    private static final Pattern PHONE_REF = Pattern.compile("[A-Z][A-Z_]*");

    private final Map<String, Buyer> byId = new LinkedHashMap<>();

    public BuyerDirectory(ObjectMapper json) {
        try (InputStream in = BuyerDirectory.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Buyer directory not found: " + RESOURCE);
            JsonNode root = json.readTree(in);
            List<Buyer> buyers = json.convertValue(root.path("buyers"), new TypeReference<>() {});
            for (Buyer b : buyers) {
                check(b);
                if (byId.putIfAbsent(b.id(), b) != null) {
                    throw new IllegalStateException("Buyer id used twice: " + b.id());
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot read the buyer directory: " + RESOURCE, e);
        }
        if (byId.isEmpty()) throw new IllegalStateException("The buyer directory is empty");
    }

    private static void check(Buyer b) {
        if (b.id() == null || b.id().isBlank() || b.name() == null || b.name().isBlank() || b.language() == null
                || b.district() == null || b.crops().isEmpty() || b.reliability() < 0 || b.reliability() > 100) {
            throw new IllegalStateException("Bad buyer entry: " + b.id());
        }
        if (b.phoneRef() == null || !PHONE_REF.matcher(b.phoneRef()).matches()) {
            throw new IllegalStateException("A buyer's phoneRef must be an env key, not a number: " + b.id());
        }
        for (Buyer.Crop c : b.crops()) {
            if (c.minPrice() <= 0 || c.maxPrice() < c.minPrice() || c.unit() == null || c.minQuantity() > c.maxQuantity()) {
                throw new IllegalStateException("Bad crop entry for buyer: " + b.id());
            }
        }
    }

    public List<Buyer> all() {
        return List.copyOf(byId.values());
    }

    public Optional<Buyer> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id.strip()));
    }
}
