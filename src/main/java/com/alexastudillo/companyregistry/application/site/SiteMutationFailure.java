package com.alexastudillo.companyregistry.application.site;

import java.util.Locale;
import java.util.Objects;

/** Known mutation outcomes; the API owns HTTP status and public response codes. */
public final class SiteMutationFailure extends RuntimeException {
    public enum Kind {
        COMPANY_NOT_FOUND, SITE_NOT_FOUND, STALE_VERSION, COMPANY_ARCHIVED, SITE_CLOSED,
        ALREADY_IN_REQUESTED_STATE, TRANSITION_NOT_ALLOWED, ACTIVE_HEADQUARTERS_REQUIRED,
        CODE_OCCUPIED, HEADQUARTERS_OCCUPIED, IDEMPOTENCY_MISMATCH, UNAVAILABLE
    }

    private final Kind kind;

    public SiteMutationFailure(Kind kind) {
        super("site-mutation-" + Objects.requireNonNull(kind, "kind").name().toLowerCase(Locale.ROOT));
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
