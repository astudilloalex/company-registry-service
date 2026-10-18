package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;

/** Retains original values and independent presence until a fingerprint policy is selected. */
public record AddressInformationPatch(FieldChange addressType, TextChanges text, PrimaryChange isPrimary,
        AddressCoordinates.Change coordinates) {
    public AddressInformationPatch {
        Objects.requireNonNull(addressType, "addressType");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(isPrimary, "isPrimary");
        Objects.requireNonNull(coordinates, "coordinates");
    }

    public static AddressInformationPatch empty() {
        return new AddressInformationPatch(FieldChange.absent(), TextChanges.empty(),
                PrimaryChange.absent(), AddressCoordinates.Change.absent());
    }

    public record TextChanges(FieldChange countryCode, FieldChange addressLine1, FieldChange administrativeDivisionCode,
            FieldChange addressLine2, FieldChange houseNumber, FieldChange postalCode) {
        public TextChanges {
            Objects.requireNonNull(countryCode, "countryCode");
            Objects.requireNonNull(addressLine1, "addressLine1");
            Objects.requireNonNull(administrativeDivisionCode, "administrativeDivisionCode");
            Objects.requireNonNull(addressLine2, "addressLine2");
            Objects.requireNonNull(houseNumber, "houseNumber");
            Objects.requireNonNull(postalCode, "postalCode");
        }

        public static TextChanges empty() {
            return new TextChanges(FieldChange.absent(), FieldChange.absent(), FieldChange.absent(),
                    FieldChange.absent(), FieldChange.absent(), FieldChange.absent());
        }
    }

    public record PrimaryChange(boolean present, Boolean value) {
        public PrimaryChange {
            if (!present && value != null) throw new IllegalArgumentException("absent-primary-has-value");
        }

        public static PrimaryChange absent() {
            return new PrimaryChange(false, null);
        }
    }
}
