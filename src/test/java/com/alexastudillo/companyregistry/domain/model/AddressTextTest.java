package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alexastudillo.companyregistry.domain.text.InvalidText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AddressTextTest {
    @Test
    void canonicalizesWithoutRemovingAccentsOrInternalSpaces() {
        var address = new AddressText(" ec ", " avenida  josé ", " ec-p ", " straße ", " 12a ", null);
        assertEquals("EC", address.countryCode());
        assertEquals("AVENIDA  JOSÉ", address.addressLine1());
        assertEquals("EC-P", address.administrativeDivisionCode());
        assertEquals("STRASSE", address.addressLine2());
        assertEquals("12A", address.houseNumber());
        assertNull(address.postalCode());
        assertEquals(AddressType.PHYSICAL, AddressText.type(" physical "));
        assertEquals(AddressType.MAILING, AddressText.type("mailing"));
    }

    @Test
    void countsCodePointsAfterUppercaseExpansionForEveryTextLimit() {
        var maximum = new AddressText("EC", "ß".repeat(150), "ß".repeat(32), "😀".repeat(300),
                "ß".repeat(16), "😀".repeat(32));
        assertEquals(300, maximum.addressLine1().length());
        assertEquals(64, maximum.administrativeDivisionCode().length());
        assertEquals(300, maximum.addressLine2().codePointCount(0, maximum.addressLine2().length()));
        assertEquals(32, maximum.houseNumber().length());
        assertEquals(32, maximum.postalCode().codePointCount(0, maximum.postalCode().length()));
        assertRule("address-line1-too-long", "ß".repeat(151), null, null, null, null);
        assertRule("address-administrative-division-code-too-long", "A", "ß".repeat(33), null, null, null);
        assertRule("address-line2-too-long", "A", null, "ß".repeat(151), null, null);
        assertRule("address-house-number-too-long", "A", null, null, "ß".repeat(17), null);
        assertRule("address-postal-code-too-long", "A", null, null, null, "ß".repeat(17));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\u2003"})
    void rejectsMissingRequiredContent(String value) {
        var country = assertThrows(InvalidText.class, () -> new AddressText(value, "A", null, null, null, null));
        assertEquals("address-country-code-required", country.rule());
        var type = assertThrows(InvalidText.class, () -> AddressText.type(value));
        assertEquals("address-type-required", type.rule());
        assertRule("address-line1-required", value, null, null, null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "\u2003"})
    void rejectsBlankOptionalContent(String value) {
        assertRule("address-administrative-division-code-blank", "A", value, null, null, null);
        assertRule("address-line2-blank", "A", null, value, null, null);
        assertRule("address-house-number-blank", "A", null, null, value, null);
        assertRule("address-postal-code-blank", "A", null, null, null, value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"E", "ECU", "ÉC", "12", "E C"})
    void requiresAlphaTwoCountry(String value) {
        var failure = assertThrows(InvalidText.class, () -> new AddressText(value, "A", null, null, null, null));
        assertEquals("address-country-code-invalid", failure.rule());
    }

    @Test
    void rejectsUnsupportedType() {
        var failure = assertThrows(InvalidText.class, () -> AddressText.type("branch"));
        assertEquals("address-type-not-supported", failure.rule());
    }

    private static void assertRule(String rule, String line1, String division, String line2, String house, String postal) {
        var failure = assertThrows(InvalidText.class, () -> new AddressText("EC", line1, division, line2, house, postal));
        assertEquals(rule, failure.rule());
    }
}
