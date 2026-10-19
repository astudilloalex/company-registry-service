package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;

/** Address values owned and versioned by Site; identity and audit are stored separately. */
public record AddressInformation(AddressType addressType, AddressText text, AddressCoordinates coordinates,
        boolean isPrimary) {
    public AddressInformation {
        Objects.requireNonNull(addressType, "addressType");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(coordinates, "coordinates");
    }
}
