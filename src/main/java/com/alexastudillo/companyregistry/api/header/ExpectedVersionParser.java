package com.alexastudillo.companyregistry.api.header;

import com.alexastudillo.companyregistry.domain.model.AggregateVersion;

/** Internal decimal If-Match convention; not an HTTP entity-tag codec. */
public final class ExpectedVersionParser {
    private ExpectedVersionParser() {
    }

    public static AggregateVersion parse(String value) {
        if (value == null || !value.matches("0|[1-9]\\d*")) {
            throw new IllegalArgumentException("if-match-invalid");
        }
        try {
            return new AggregateVersion(Long.parseLong(value));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("if-match-out-of-range", failure);
        }
    }
}
