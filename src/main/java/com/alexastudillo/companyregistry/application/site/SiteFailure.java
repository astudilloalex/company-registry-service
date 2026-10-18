package com.alexastudillo.companyregistry.application.site;

/** Known use-case outcomes; presentation owns their public status and code. */
public final class SiteFailure extends RuntimeException {
    public enum Kind {
        COMPANY_NOT_FOUND, SITE_NOT_FOUND, COMPANY_ARCHIVED, CODE_OCCUPIED, HEADQUARTERS_OCCUPIED,
        IDEMPOTENCY_MISMATCH, CURSOR_INVALID, UNAVAILABLE
    }

    private final Kind kind;

    public SiteFailure(Kind kind) {
        super("site-" + kind.name().toLowerCase(java.util.Locale.ROOT));
        this.kind = java.util.Objects.requireNonNull(kind);
    }

    public Kind kind() { return kind; }
}
