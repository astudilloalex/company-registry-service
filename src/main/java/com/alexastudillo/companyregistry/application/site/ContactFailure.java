package com.alexastudillo.companyregistry.application.site;

/** Publicly classifiable outcomes; presentation supplies status/code only. */
public final class ContactFailure extends RuntimeException {
    public enum Kind {
        COMPANY_NOT_FOUND, SITE_NOT_FOUND, CONTACT_NOT_FOUND, STALE_VERSION, COMPANY_ARCHIVED, SITE_CLOSED,
        IDEMPOTENCY_MISMATCH, CONTACT_VALUE, UNAVAILABLE, CURSOR_INVALID
    }

    private final Kind kind;

    public ContactFailure(Kind kind) {
        super("contact-" + kind.name().toLowerCase(java.util.Locale.ROOT));
        this.kind = java.util.Objects.requireNonNull(kind);
    }

    public Kind kind() { return kind; }
}
