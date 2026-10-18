package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alexastudillo.companyregistry.domain.text.InvalidText;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AddressCoordinatesTest {
    @ParameterizedTest
    @CsvSource({"90,180", "-90,-180", "0,0", "-0.123456,0.123456", "90.0000000,180.00000000"})
    void acceptsExactValuesAndInclusiveLimits(String latitude, String longitude) {
        var pair = pair(latitude, longitude);
        assertEquals(new BigDecimal(latitude).stripTrailingZeros(), pair.latitude());
        assertEquals(new BigDecimal(longitude).stripTrailingZeros(), pair.longitude());
    }

    @ParameterizedTest
    @CsvSource({"90.0000001,0,address-latitude-out-of-range", "-90.0000001,0,address-latitude-out-of-range",
            "0,180.0000001,address-longitude-out-of-range", "0,-180.0000001,address-longitude-out-of-range",
            "0.1234567,0,address-latitude-precision-exceeded", "0,-0.1234567,address-longitude-precision-exceeded",
            "0.1234567,180.0000001,address-longitude-out-of-range"})
    void rejectsBeforeRoundingAndChecksBothRangesBeforePrecision(String latitude, String longitude, String rule) {
        var failure = assertThrows(InvalidText.class, () -> pair(latitude, longitude));
        assertEquals(rule, failure.rule());
    }

    @Test
    void equatesNumericValuesDespiteScaleAndNegativeZero() {
        assertEquals(pair("-0.123456", "10"), pair("-0.1234560", "10.0000000"));
        assertEquals(pair("0", "0"), pair("-0.000000000", "0.00000000"));
    }

    @Test
    void omissionPreservesPairAndExplicitNullClearsIt() {
        var previous = pair("1", "2");
        assertSame(previous, AddressCoordinates.Change.absent().apply(previous));
        var clear = new AddressCoordinates.Change(true, null, true, null);
        assertEquals(AddressCoordinates.empty(), clear.apply(previous));
        assertEquals(AddressCoordinates.empty(), clear.apply(AddressCoordinates.empty()));
    }

    @Test
    void rejectsMissingMemberEvenIfPreviousPairExists() {
        var previous = pair("1", "2");
        for (boolean latitudePresent : new boolean[] {true, false}) {
            var change = new AddressCoordinates.Change(latitudePresent, null, !latitudePresent, null);
            var failure = assertThrows(InvalidText.class, () -> change.apply(previous));
            assertEquals("address-coordinates-must-be-provided-together", failure.rule());
        }
    }

    @Test
    void rejectsMixedNullAndNumber() {
        var number = BigDecimal.ONE;
        var latitude = assertThrows(InvalidText.class, () -> new AddressCoordinates(number, null));
        var longitude = assertThrows(InvalidText.class, () -> new AddressCoordinates(null, number));
        assertEquals("address-coordinates-must-be-both-null-or-numeric", latitude.rule());
        assertEquals(latitude.rule(), longitude.rule());
    }

    private static AddressCoordinates pair(String latitude, String longitude) {
        return new AddressCoordinates(new BigDecimal(latitude), new BigDecimal(longitude));
    }
}
