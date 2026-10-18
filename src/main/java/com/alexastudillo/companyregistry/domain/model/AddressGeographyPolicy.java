package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;

/** Only new assignments need authoritative eligibility; removing a division is entirely local. */
public final class AddressGeographyPolicy {
    private AddressGeographyPolicy() {}

    public record Plan(String countryCode, String administrativeDivisionCode, boolean required) {}

    public enum Eligibility { ELIGIBLE, COUNTRY_NOT_FOUND, COUNTRY_INACTIVE, DIVISION_NOT_FOUND, DIVISION_INACTIVE }

    public static Plan plan(AddressText previous, AddressText effective) {
        Objects.requireNonNull(effective, "effective");
        boolean countryChanged = previous == null || !previous.countryCode().equals(effective.countryCode());
        boolean divisionAssigned = effective.administrativeDivisionCode() != null
                && (previous == null || !Objects.equals(previous.administrativeDivisionCode(), effective.administrativeDivisionCode()));
        return new Plan(effective.countryCode(), effective.administrativeDivisionCode(), countryChanged || divisionAssigned);
    }

    public static Eligibility country(boolean exists, boolean active) {
        if (!exists) return Eligibility.COUNTRY_NOT_FOUND;
        return active ? Eligibility.ELIGIBLE : Eligibility.COUNTRY_INACTIVE;
    }

    public static Eligibility division(boolean exists, boolean active) {
        if (!exists) return Eligibility.DIVISION_NOT_FOUND;
        return active ? Eligibility.ELIGIBLE : Eligibility.DIVISION_INACTIVE;
    }
}
