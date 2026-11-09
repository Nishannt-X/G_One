package com.example.g_one.advisory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FreightEstimatorTest {
    private final FreightEstimator estimator = new FreightEstimator();
    private final FreightEstimatorTool tool = new FreightEstimatorTool();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void resolvesDirectRoute() {
        var dist = estimator.shortestDistanceKm("Niphad", "Lasalgaon");
        assertThat(dist).isPresent();
        assertThat(dist.get()).isEqualTo(18);
    }

    @Test
    void resolvesMultiHopRoute() {
        // Niphad -> Nashik (40) -> Vashi (170) = 210 km
        var dist = estimator.shortestDistanceKm("Niphad", "Vashi");
        assertThat(dist).isPresent();
        assertThat(dist.get()).isEqualTo(210);
    }

    @Test
    void resolvesHindiPlaceNames() {
        var dist = estimator.shortestDistanceKm("निफाड", "लासलगांव");
        assertThat(dist).isPresent();
        assertThat(dist.get()).isEqualTo(18);
    }

    @Test
    void selectsAppropriateVehicleByQuantity() {
        var v1 = estimator.selectVehicleForQuantity(6);
        assertThat(v1.id()).isEqualTo("mini_truck");

        var v2 = estimator.selectVehicleForQuantity(14);
        assertThat(v2.id()).isEqualTo("pickup");

        var v3 = estimator.selectVehicleForQuantity(35);
        assertThat(v3.id()).isEqualTo("tractor_trolley");
    }

    @Test
    void estimatesFreightWithMinimumTripFloor() {
        // Niphad to Lasalgaon: 18 km. Pickup rate: 22 Rs/km = 396 Rs. Minimum trip: 600 Rs.
        var est = estimator.estimate("Niphad", "Lasalgaon", null, "pickup", 15);
        assertThat(est.distanceKm()).isEqualTo(18);
        assertThat(est.totalTripCostRupees()).isEqualTo(600.0);
        assertThat(est.freightRupeesPerQuintal()).isEqualTo(40.0); // 600 / 15 = 40 Rs/quintal
    }

    @Test
    void comparesFarmGateBetterThanMandi() {
        // Niphad to Lasalgaon: 18 km, 15 quintals pickup -> freight 40 Rs/quintal.
        // Mandi price = 2420, Mandi net = 2420 - 40 = 2380.
        // Farm-gate offer = 2400. Farm-gate is +20 Rs better!
        var cmp = estimator.compareFarmGateVsMandi("Niphad", "Lasalgaon", null, "pickup", 15, 2400, 2420);
        assertThat(cmp.farmGateBetter()).isTrue();
        assertThat(cmp.mandiNetRealization()).isEqualTo(2380.0);
        assertThat(cmp.differenceRupeesPerQuintal()).isEqualTo(20.0);
        assertThat(cmp.adviceEn()).contains("more profitable by ₹20/quintal than selling at Lasalgaon mandi");
        assertThat(cmp.adviceHi()).contains("अधिक लाभदायक है");
    }

    @Test
    void comparesMandiBetterThanFarmGate() {
        // Farm-gate offer = 2100. Mandi price = 2500, freight = 40. Mandi net = 2460.
        // Mandi is +360 Rs better!
        var cmp = estimator.compareFarmGateVsMandi("Niphad", "Lasalgaon", null, "pickup", 15, 2100, 2500);
        assertThat(cmp.farmGateBetter()).isFalse();
        assertThat(cmp.mandiNetRealization()).isEqualTo(2460.0);
        assertThat(cmp.differenceRupeesPerQuintal()).isEqualTo(-360.0);
        assertThat(cmp.adviceEn()).contains("better than the farm-gate offer");
        assertThat(cmp.adviceHi()).contains("बेहतर है");
    }

    @Test
    void toolInvocationWithComparison() throws Exception {
        var args = JSON.createObjectNode()
                .put("from", "Niphad")
                .put("to", "Lasalgaon")
                .put("vehicle", "pickup")
                .put("quantity", 15)
                .put("farmGatePrice", 2400)
                .put("mandiPrice", 2420);

        ToolResult res = tool.invoke(args, null);
        assertThat(res.ok()).isTrue();
        var json = JSON.readTree(res.content());
        assertThat(json.get("farmGateBetter").asBoolean()).isTrue();
        assertThat(json.get("freightRupeesPerQuintal").asDouble()).isEqualTo(40.0);
    }
}
