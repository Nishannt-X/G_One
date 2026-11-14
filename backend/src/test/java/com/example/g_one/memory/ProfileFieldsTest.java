package com.example.g_one.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The profile whitelist: what may be stored, in what form. */
class ProfileFieldsTest {

    private static String stored(String field, String value) {
        return ProfileFields.check(field, value).map(ProfileFields.Checked::value).orElse(null);
    }

    @Test
    void namesAndPlacesAreStoredInEnglishLetters() {
        assertThat(ProfileFields.check("name", "रमेश पाटिल")).contains(new ProfileFields.Checked("name", "Ramesh Patil"));
        assertThat(stored("district", "নদিয়া")).isEqualTo("Nadiya");
        assertThat(stored("usualMandi", "Lasalgaon APMC")).isEqualTo("Lasalgaon APMC");
        assertThat(ProfileFields.check("name", "王伟")).as("no English letters left: not stored").isEmpty();
    }

    @ParameterizedTest(name = "{0}={1} -> {2}")
    @CsvSource({
        "transport, Own Vehicle, own vehicle",
        "transport, own tractor, own vehicle",
        "transport, needs a truck, needs truck",
        "transport, buyer picks up, buyer arranges",
        "paymentPreference, upi, UPI",
        "paymentPreference, GPay, UPI",
        "paymentPreference, Cash, cash",
        "paymentPreference, NEFT, bank transfer",
        "paymentPreference, cash on delivery, on delivery",
        "storageAccess, yes, has cold storage",
        "storageAccess, Cold storage nearby, cold storage nearby",
        "storageAccess, no, no cold storage",
        "typicalQuantity, 40 quintal per sale, 40 quintal per sale",
        "language, hi_en, HI_EN",
    })
    void newFieldsTakeTheirFixedValues(String field, String value, String expected) {
        assertThat(ProfileFields.check(field, value)).contains(new ProfileFields.Checked(field, expected));
    }

    @ParameterizedTest(name = "{0}={1} rejected")
    @CsvSource({
        "transport, bullock cart maybe",
        "paymentPreference, crypto",
        "storageAccess, sometimes",
        "typicalQuantity, a lot",
        "typicalQuantity, 40 क्विंटल",
        "typicalQuantity, 40 quintal and then some more every single week",
        "language, Tamil",
        "phone, 9876543210",
        "buyerName, Shinde Traders",
        "name, call me on 98765 43210",
        "village, Plot 4567891",
    })
    void badValuesAndUnknownFieldsAreRejected(String field, String value) {
        assertThat(ProfileFields.check(field, value)).isEmpty();
        assertThat(ProfileFields.allowed(field, value)).isEmpty();
    }

    @Test
    void cropsKeepOneFieldEach() {
        assertThat(ProfileFields.check("crops", "Red  Onion")).contains(new ProfileFields.Checked("crop:red onion", "Red Onion"));
        assertThat(ProfileFields.allowed("crop:Garlic", "garlic")).contains("crop:garlic");
    }

    @ParameterizedTest
    @ValueSource(strings = {"typicalQuantity", "transport", "paymentPreference", "storageAccess"})
    void theNewFarmerPrivateFieldsAreInTheWhitelistAndOrder(String field) {
        assertThat(ProfileFields.SINGLE).contains(field);
        assertThat(ProfileFields.ORDER).contains(field);
    }

    /**
     * The buyer's answers are built only from AskDigest.Facts: the deal terms on the call and the farmer's name. No
     * profile field may appear there, so the buyer never learns the farmer's quantity, transport, payment or storage.
     */
    @Test
    void theBuyerFactsHaveNoProfileField() throws Exception {
        RecordComponent[] parts = Class.forName("com.example.g_one.agent.mediation.AskDigest$Facts").getRecordComponents();
        var names = Arrays.stream(parts).map(p -> p.getName().toLowerCase(Locale.ROOT)).toList();
        for (String field : ProfileFields.ORDER) {
            // The introduction names the farmer; "state" there is the deal's state, not the farmer's home state.
            if (field.equals(ProfileFields.NAME) || field.equals(ProfileFields.STATE)) continue;
            assertThat(names).as(field).doesNotContain(field.toLowerCase(Locale.ROOT));
        }
        assertThat(names).noneMatch(n -> n.contains("storage") || n.contains("transport") || n.contains("typical")
                || n.contains("mandi") || n.contains("village") || n.contains("district"));
    }
}
