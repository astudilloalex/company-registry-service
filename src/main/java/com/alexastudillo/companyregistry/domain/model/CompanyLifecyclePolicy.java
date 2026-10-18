package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;

/** Evaluate after establishing ownership and the expected Company revision. */
public final class CompanyLifecyclePolicy {
    private CompanyLifecyclePolicy() {}

    public static CompanyStatus transition(CompanyStatus source, CompanyStatus target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source.terminal()) reject(CompanyLifecycleRejected.Reason.COMPANY_ARCHIVED);
        if (source == target) reject(CompanyLifecycleRejected.Reason.ALREADY_IN_REQUESTED_STATE);
        if (!source.permitsTransitionTo(target)) reject(CompanyLifecycleRejected.Reason.TRANSITION_NOT_ALLOWED);
        return target;
    }

    private static void reject(CompanyLifecycleRejected.Reason reason) {
        throw new CompanyLifecycleRejected(reason);
    }
}
