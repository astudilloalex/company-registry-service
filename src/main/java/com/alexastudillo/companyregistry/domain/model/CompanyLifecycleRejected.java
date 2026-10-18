package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;

/** Business rejection, independent of the transport that presents it. */
public final class CompanyLifecycleRejected extends IllegalStateException {
    public enum Reason {
        COMPANY_ARCHIVED, ALREADY_IN_REQUESTED_STATE, TRANSITION_NOT_ALLOWED,
        LEGAL_HEADQUARTERS_REQUIRED, ACTIVE_HEADQUARTERS_REQUIRED,
        PARTY_NOT_FOUND, PARTY_MUST_BE_LEGAL_ENTITY, PARTY_MUST_BE_ACTIVE
    }

    private final Reason reason;

    public CompanyLifecycleRejected(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
