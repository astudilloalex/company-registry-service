package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Separate aggregate; addresses and contacts share this aggregate's revision. */
public record Site(UUID id, UUID tenantId, UUID companyId, SiteType type,
                   SiteStatus status, AggregateVersion version) {
    public Site {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(version, "version");
    }

    public boolean operationalUnder(Company company) {
        Objects.requireNonNull(company, "company");
        return company.id().equals(companyId) && company.tenantId().equals(tenantId)
                && company.status() == CompanyStatus.ACTIVE && status == SiteStatus.ACTIVE;
    }

    public boolean canPrepareActivationUnder(Company company) {
        Objects.requireNonNull(company, "company");
        return company.id().equals(companyId) && company.tenantId().equals(tenantId)
                && !company.status().terminal() && status.permitsTransitionTo(SiteStatus.ACTIVE);
    }
}
