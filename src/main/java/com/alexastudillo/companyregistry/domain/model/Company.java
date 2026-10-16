package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Minimal aggregate identity/state; legal identity remains in Party Registry. */
public record Company(UUID id, UUID tenantId, UUID partyId, CompanyStatus status, AggregateVersion version) {
    public Company {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(version, "version");
    }
}
