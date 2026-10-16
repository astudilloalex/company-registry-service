package com.alexastudillo.companyregistry.domain.model;

/** Independent Company or Site revision; never the outbox delivery revision. */
public record AggregateVersion(long value) {
    public AggregateVersion {
        if (value < 0) {
            throw new IllegalArgumentException("aggregate-version-negative");
        }
    }

    public static AggregateVersion initial() {
        return new AggregateVersion(0);
    }

    public AggregateVersion next() {
        return new AggregateVersion(Math.incrementExact(value));
    }
}
