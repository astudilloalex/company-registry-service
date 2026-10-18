package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;

/** Evaluate only after ownership and the expected Site revision have been established. */
public final class SiteLifecyclePolicy {
    private SiteLifecyclePolicy() {}

    public static void requireEditable(CompanyStatus company, SiteStatus site) {
        Objects.requireNonNull(company, "company");
        Objects.requireNonNull(site, "site");
        if (company == CompanyStatus.ARCHIVED) reject(SiteMutationRejected.Reason.COMPANY_ARCHIVED);
        if (site == SiteStatus.CLOSED) reject(SiteMutationRejected.Reason.SITE_CLOSED);
    }

    public static SiteStatus transition(CompanyStatus company, SiteType type, SiteStatus source, SiteStatus target) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        requireEditable(company, source);
        if (source == target) reject(SiteMutationRejected.Reason.ALREADY_IN_REQUESTED_STATE);
        if (!source.permitsTransitionTo(target)) reject(SiteMutationRejected.Reason.TRANSITION_NOT_ALLOWED);
        requireHeadquarters(company, type, source, type, target);
        return target;
    }

    public static void requireHeadquarters(CompanyStatus company, SiteType previousType, SiteStatus previousStatus,
            SiteType resultingType, SiteStatus resultingStatus) {
        Objects.requireNonNull(company, "company");
        Objects.requireNonNull(previousType, "previousType");
        Objects.requireNonNull(previousStatus, "previousStatus");
        Objects.requireNonNull(resultingType, "resultingType");
        Objects.requireNonNull(resultingStatus, "resultingStatus");
        if (company == CompanyStatus.ACTIVE && previousType == SiteType.HEADQUARTERS
                && previousStatus == SiteStatus.ACTIVE
                && (resultingType != SiteType.HEADQUARTERS || resultingStatus != SiteStatus.ACTIVE)) {
            reject(SiteMutationRejected.Reason.ACTIVE_HEADQUARTERS_REQUIRED);
        }
    }

    private static void reject(SiteMutationRejected.Reason reason) {
        throw new SiteMutationRejected(reason);
    }
}
