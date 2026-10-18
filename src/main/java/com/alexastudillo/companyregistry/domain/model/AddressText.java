package com.alexastudillo.companyregistry.domain.model;

import com.alexastudillo.companyregistry.domain.text.BusinessText;
import com.alexastudillo.companyregistry.domain.text.InvalidText;

/** Canonical postal content, independent of transport and geographic observations. */
public record AddressText(String countryCode, String addressLine1, String administrativeDivisionCode,
        String addressLine2, String houseNumber, String postalCode) {
    public AddressText {
        countryCode = country(countryCode);
        addressLine1 = text(addressLine1, 300, "address-line1", true);
        administrativeDivisionCode = text(administrativeDivisionCode, 64, "address-administrative-division-code", false);
        addressLine2 = text(addressLine2, 300, "address-line2", false);
        houseNumber = text(houseNumber, 32, "address-house-number", false);
        postalCode = text(postalCode, 32, "address-postal-code", false);
    }

    static String country(String value) {
        String canonical = BusinessText.canonical(value);
        if (canonical == null || canonical.isBlank()) throw new InvalidText("address-country-code-required");
        if (!canonical.matches("[A-Z]{2}")) throw new InvalidText("address-country-code-invalid");
        return canonical;
    }

    static String text(String value, int limit, String field, boolean required) {
        try {
            return required ? BusinessText.required(value, limit) : BusinessText.optional(value, limit);
        } catch (InvalidText failure) {
            if ("text-too-long".equals(failure.rule())) {
                throw new InvalidText(field + "-too-long");
            }
            throw new InvalidText(field + (required ? "-required" : "-blank"));
        }
    }

    public static AddressType type(String value) {
        String canonical = BusinessText.canonical(value);
        if (canonical == null || canonical.isBlank()) throw new InvalidText("address-type-required");
        try {
            return AddressType.valueOf(canonical);
        } catch (IllegalArgumentException _) {
            throw new InvalidText("address-type-not-supported");
        }
    }
}
