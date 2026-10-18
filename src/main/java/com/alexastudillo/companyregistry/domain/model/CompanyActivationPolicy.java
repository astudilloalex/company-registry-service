package com.alexastudillo.companyregistry.domain.model;

/** Eligibility observations only; obtaining them belongs to the application. */
public final class CompanyActivationPolicy {
    private CompanyActivationPolicy() {}

    public record PartyFacts(boolean legalEntity, boolean active) {}

    public static void requireHeadquarters(SiteStatus currentStatus) {
        if (currentStatus == null || currentStatus == SiteStatus.CLOSED) {
            throw new CompanyLifecycleRejected(CompanyLifecycleRejected.Reason.LEGAL_HEADQUARTERS_REQUIRED);
        }
        if (currentStatus != SiteStatus.ACTIVE) {
            throw new CompanyLifecycleRejected(CompanyLifecycleRejected.Reason.ACTIVE_HEADQUARTERS_REQUIRED);
        }
    }

    public static void requireParty(PartyFacts party) {
        if (party == null) throw new CompanyLifecycleRejected(CompanyLifecycleRejected.Reason.PARTY_NOT_FOUND);
        if (!party.legalEntity()) {
            throw new CompanyLifecycleRejected(CompanyLifecycleRejected.Reason.PARTY_MUST_BE_LEGAL_ENTITY);
        }
        if (!party.active()) throw new CompanyLifecycleRejected(CompanyLifecycleRejected.Reason.PARTY_MUST_BE_ACTIVE);
    }
}
