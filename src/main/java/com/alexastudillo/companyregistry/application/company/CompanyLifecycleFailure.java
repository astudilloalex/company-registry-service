package com.alexastudillo.companyregistry.application.company;

import java.util.Locale;
import java.util.Objects;

/** Known outcomes; presentation owns HTTP statuses and public codes. */
public final class CompanyLifecycleFailure extends RuntimeException {
    public enum Kind {
        COMPANY_NOT_FOUND, STALE_VERSION, COMPANY_ARCHIVED, ALREADY_IN_REQUESTED_STATE, TRANSITION_NOT_ALLOWED,
        LEGAL_HEADQUARTERS_REQUIRED, ACTIVE_HEADQUARTERS_REQUIRED,
        PARTY_NOT_FOUND, PARTY_MUST_BE_LEGAL_ENTITY, PARTY_MUST_BE_ACTIVE,
        IDEMPOTENCY_MISMATCH, UNAVAILABLE
    }

    private final Kind kind;

    public CompanyLifecycleFailure(Kind kind) {
        super("company-lifecycle-" + Objects.requireNonNull(kind, "kind").name().toLowerCase(Locale.ROOT));
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
