package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;

/** A local lifecycle decision; transport mappings belong to the API. */
public final class SiteMutationRejected extends IllegalStateException {
    public enum Reason {
        COMPANY_ARCHIVED, SITE_CLOSED, ALREADY_IN_REQUESTED_STATE, TRANSITION_NOT_ALLOWED,
        ACTIVE_HEADQUARTERS_REQUIRED
    }

    private final Reason reason;

    public SiteMutationRejected(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
