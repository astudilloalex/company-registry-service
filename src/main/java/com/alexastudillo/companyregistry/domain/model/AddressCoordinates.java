package com.alexastudillo.companyregistry.domain.model;

import com.alexastudillo.companyregistry.domain.text.InvalidText;
import java.math.BigDecimal;

/** Exact geographic pair. Canonical BigDecimals make record equality numeric equality. */
public record AddressCoordinates(BigDecimal latitude, BigDecimal longitude) {
    private static final BigDecimal LATITUDE_LIMIT = BigDecimal.valueOf(90);
    private static final BigDecimal LONGITUDE_LIMIT = BigDecimal.valueOf(180);

    public AddressCoordinates {
        if ((latitude == null) != (longitude == null)) {
            throw new InvalidText("address-coordinates-must-be-both-null-or-numeric");
        }
        range(latitude, LATITUDE_LIMIT, "address-latitude");
        range(longitude, LONGITUDE_LIMIT, "address-longitude");
        latitude = exact(latitude, "address-latitude");
        longitude = exact(longitude, "address-longitude");
    }

    public static AddressCoordinates empty() {
        return new AddressCoordinates(null, null);
    }

    private static void range(BigDecimal value, BigDecimal limit, String field) {
        if (value != null && value.abs().compareTo(limit) > 0) throw new InvalidText(field + "-out-of-range");
    }

    private static BigDecimal exact(BigDecimal value, String field) {
        if (value == null) return null;
        BigDecimal canonical = value.stripTrailingZeros();
        if (canonical.scale() > 6) throw new InvalidText(field + "-precision-exceeded");
        return canonical;
    }

    /** Presence of both members survives independently of their nullable values. */
    public record Change(boolean latitudePresent, BigDecimal latitude, boolean longitudePresent, BigDecimal longitude) {
        public Change {
            if ((!latitudePresent && latitude != null) || (!longitudePresent && longitude != null)) {
                throw new IllegalArgumentException("absent-coordinate-has-value");
            }
        }

        public static Change absent() {
            return new Change(false, null, false, null);
        }

        public boolean present() {
            return latitudePresent || longitudePresent;
        }

        public Change canonical() {
            if (latitudePresent != longitudePresent) {
                throw new InvalidText("address-coordinates-must-be-provided-together");
            }
            if (!present()) return this;
            var pair = new AddressCoordinates(latitude, longitude);
            return new Change(true, pair.latitude(), true, pair.longitude());
        }

        public AddressCoordinates apply(AddressCoordinates previous) {
            var canonical = canonical();
            return canonical.present() ? new AddressCoordinates(canonical.latitude(), canonical.longitude()) : previous;
        }
    }
}
