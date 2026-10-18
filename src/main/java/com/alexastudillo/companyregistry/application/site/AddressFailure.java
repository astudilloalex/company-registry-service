package com.alexastudillo.companyregistry.application.site;

/** Publicly classifiable outcomes; presentation supplies status/code only. */
public final class AddressFailure extends RuntimeException {
    public enum Kind {
        COMPANY_NOT_FOUND, SITE_NOT_FOUND, ADDRESS_NOT_FOUND, STALE_VERSION, COMPANY_ARCHIVED, SITE_CLOSED,
        IDEMPOTENCY_MISMATCH, COUNTRY_NOT_FOUND, COUNTRY_INACTIVE, DIVISION_NOT_FOUND, DIVISION_INACTIVE,
        DEPENDENCY_INVALID, UNAVAILABLE, CURSOR_INVALID
    }

    private final Kind kind;

    public AddressFailure(Kind kind) {
        super("address-" + kind.name().toLowerCase(java.util.Locale.ROOT));
        this.kind = java.util.Objects.requireNonNull(kind);
    }

    public Kind kind() { return kind; }
}
