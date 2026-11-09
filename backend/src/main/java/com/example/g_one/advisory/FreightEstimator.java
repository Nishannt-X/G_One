package com.example.g_one.advisory;

import com.example.g_one.agent.llm.SpokenNumbers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Calculates synthetic road freight costs based on distance and vehicle types,
 * and compares farm-gate offers against mandi net realizations.
 */
public class FreightEstimator {

    public record Vehicle(
            String id,
            String en,
            String hi,
            int capacityQuintals,
            int rupeesPerKm,
            int minimumTrip,
            List<String> aliases
    ) {}

    public record FreightEstimate(
            String origin,
            String destination,
            int distanceKm,
            Vehicle vehicle,
            double quantityQuintals,
            double totalTripCostRupees,
            double freightRupeesPerQuintal
    ) {}

    public record NetComparison(
            FreightEstimate freight,
            double farmGatePrice,
            double mandiPrice,
            double mandiNetRealization,
            double differenceRupeesPerQuintal,
            boolean farmGateBetter,
            String adviceEn,
            String adviceHi
    ) {}

    private final Map<String, String> placeAliases = new HashMap<>();
    private final Map<String, Map<String, Integer>> graph = new HashMap<>();
    private final List<Vehicle> vehicles = new ArrayList<>();
    private final Map<String, Vehicle> vehicleAliases = new HashMap<>();

    public FreightEstimator() {
        this("/advisory/freight-table.json");
    }

    public FreightEstimator(String resourcePath) {
        loadData(resourcePath);
    }

    private void loadData(String resourcePath) {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                try (InputStream fallback = getClass().getResourceAsStream("/advisory/freight-table.sample.json")) {
                    if (fallback != null) {
                        parse(mapper.readTree(fallback));
                    }
                }
                return;
            }
            parse(mapper.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load freight table from " + resourcePath, e);
        }
    }

    private void parse(JsonNode root) {
        // Places
        for (JsonNode p : root.path("places")) {
            String canonical = p.path("name").asText();
            placeAliases.put(canonical.toLowerCase(Locale.ROOT), canonical);
            placeAliases.put(p.path("hindi").asText().toLowerCase(Locale.ROOT), canonical);
            for (JsonNode a : p.path("aliases")) {
                placeAliases.put(a.asText().trim().toLowerCase(Locale.ROOT), canonical);
            }
        }

        // Routes
        for (JsonNode r : root.path("routes")) {
            String from = r.path("from").asText();
            String to = r.path("to").asText();
            int km = r.path("km").asInt();
            graph.computeIfAbsent(from, k -> new HashMap<>()).put(to, km);
            graph.computeIfAbsent(to, k -> new HashMap<>()).put(from, km);
        }

        // Vehicles
        for (JsonNode v : root.path("vehicles")) {
            String id = v.path("id").asText();
            String en = v.path("en").asText();
            String hi = v.path("hi").asText();
            int cap = v.path("capacityQuintals").asInt();
            int perKm = v.path("rupeesPerKm").asInt();
            int minTrip = v.path("minimumTrip").asInt();
            List<String> aliases = new ArrayList<>();
            aliases.add(id.toLowerCase(Locale.ROOT));
            aliases.add(en.toLowerCase(Locale.ROOT));
            aliases.add(hi.toLowerCase(Locale.ROOT));
            for (JsonNode a : v.path("aliases")) {
                aliases.add(a.asText().trim().toLowerCase(Locale.ROOT));
            }
            Vehicle veh = new Vehicle(id, en, hi, cap, perKm, minTrip, Collections.unmodifiableList(aliases));
            vehicles.add(veh);
            for (String a : aliases) {
                vehicleAliases.putIfAbsent(a, veh);
            }
        }
    }

    public Optional<String> canonicalPlace(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String key = raw.trim().toLowerCase(Locale.ROOT);
        String found = placeAliases.get(key);
        if (found != null) return Optional.of(found);
        for (Map.Entry<String, String> e : placeAliases.entrySet()) {
            if (key.contains(e.getKey()) || e.getKey().contains(key)) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    public Optional<Vehicle> findVehicle(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String key = raw.trim().toLowerCase(Locale.ROOT);
        Vehicle v = vehicleAliases.get(key);
        if (v != null) return Optional.of(v);
        for (Map.Entry<String, Vehicle> e : vehicleAliases.entrySet()) {
            if (key.contains(e.getKey()) || e.getKey().contains(key)) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    public Vehicle selectVehicleForQuantity(double quintals) {
        for (Vehicle v : vehicles) {
            if (v.capacityQuintals() >= quintals) {
                return v;
            }
        }
        return vehicles.getLast(); // default to largest truck
    }

    public Optional<Integer> shortestDistanceKm(String fromPlace, String toPlace) {
        String from = canonicalPlace(fromPlace).orElse(fromPlace);
        String to = canonicalPlace(toPlace).orElse(toPlace);
        if (from.equalsIgnoreCase(to)) return Optional.of(0);

        Map<String, Integer> dist = new HashMap<>();
        PriorityQueue<Map.Entry<String, Integer>> pq = new PriorityQueue<>(Map.Entry.comparingByValue());
        dist.put(from, 0);
        pq.add(Map.entry(from, 0));

        Set<String> visited = new HashSet<>();

        while (!pq.isEmpty()) {
            Map.Entry<String, Integer> curr = pq.poll();
            String u = curr.getKey();
            int d = curr.getValue();
            if (visited.contains(u)) continue;
            visited.add(u);
            if (u.equalsIgnoreCase(to)) return Optional.of(d);

            Map<String, Integer> neighbors = graph.get(u);
            if (neighbors != null) {
                for (Map.Entry<String, Integer> edge : neighbors.entrySet()) {
                    String v = edge.getKey();
                    int weight = edge.getValue();
                    int nextDist = d + weight;
                    if (nextDist < dist.getOrDefault(v, Integer.MAX_VALUE)) {
                        dist.put(v, nextDist);
                        pq.add(Map.entry(v, nextDist));
                    }
                }
            }
        }
        return Optional.empty();
    }

    public FreightEstimate estimate(String from, String to, Integer manualKm, String vehicleName, double quantityQuintals) {
        int km;
        if (manualKm != null && manualKm > 0) {
            km = manualKm;
        } else {
            km = shortestDistanceKm(from, to).orElse(30); // default 30 km if unmapped
        }

        Vehicle vehicle = findVehicle(vehicleName).orElseGet(() -> selectVehicleForQuantity(quantityQuintals));
        double tripCost = Math.max(vehicle.minimumTrip(), (double) km * vehicle.rupeesPerKm());
        double effQuantity = quantityQuintals > 0 ? quantityQuintals : vehicle.capacityQuintals();
        double perQuintal = Math.round((tripCost / effQuantity) * 10.0) / 10.0;

        String canonFrom = canonicalPlace(from).orElse(from);
        String canonTo = canonicalPlace(to).orElse(to);

        return new FreightEstimate(
                canonFrom, canonTo, km, vehicle, effQuantity, tripCost, perQuintal
        );
    }

    public NetComparison compareFarmGateVsMandi(
            String from,
            String toMandi,
            Integer manualKm,
            String vehicleName,
            double quantityQuintals,
            double farmGatePrice,
            double mandiPrice
    ) {
        FreightEstimate est = estimate(from, toMandi, manualKm, vehicleName, quantityQuintals);
        double mandiNet = mandiPrice - est.freightRupeesPerQuintal();
        double diff = farmGatePrice - mandiNet;
        boolean farmGateBetter = diff >= 0;

        long fgRound = Math.round(farmGatePrice);
        long mndRound = Math.round(mandiPrice);
        long netRound = Math.round(mandiNet);
        long freightRound = Math.round(est.freightRupeesPerQuintal());
        long diffRound = Math.abs(Math.round(diff));

        String en;
        String hi;

        String fgWords = SpokenNumbers.words(fgRound, "hi-IN");
        String mndWords = SpokenNumbers.words(mndRound, "hi-IN");
        String netWords = SpokenNumbers.words(netRound, "hi-IN");
        String freightWords = SpokenNumbers.words(freightRound, "hi-IN");
        String diffWords = SpokenNumbers.words(diffRound, "hi-IN");

        if (farmGateBetter) {
            en = String.format(
                    "Farm-gate offer of ₹%d/quintal is more profitable by ₹%d/quintal than selling at %s mandi (mandi net: ₹%d/quintal after ₹%d freight).",
                    fgRound, diffRound, est.destination(), netRound, freightRound
            );
            hi = String.format(
                    "खेत पर %s रुपये प्रति क्विंटल का भाव %s मंडी जाने से %s रुपये अधिक लाभदायक है (भाड़ा %s रुपये कटने के बाद मंडी में शुद्ध भाव %s रुपये बचेगा)।",
                    fgWords, est.destination(), diffWords, freightWords, netWords
            );
        } else {
            en = String.format(
                    "Selling at %s mandi yields net ₹%d/quintal (after ₹%d freight on ₹%d mandi price), which is ₹%d/quintal better than the farm-gate offer of ₹%d/quintal.",
                    est.destination(), netRound, freightRound, mndRound, diffRound, fgRound
            );
            hi = String.format(
                    "%s मंडी में %s रुपये भाड़ा देने के बाद भी शुद्ध %s रुपये प्रति क्विंटल मिलेगा, जो खेत पर दिए गए %s रुपये से %s रुपये प्रति क्विंटल बेहतर है।",
                    est.destination(), freightWords, netWords, fgWords, diffWords
            );
        }

        return new NetComparison(
                est,
                farmGatePrice,
                mandiPrice,
                mandiNet,
                diff,
                farmGateBetter,
                en,
                hi
        );
    }
}
