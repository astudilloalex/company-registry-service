package com.alexastudillo.companyregistry.application.site;

import java.util.Objects;
import java.util.UUID;

/** Complete ownership required by every contact query and acceptance. */
public record ContactScope(UUID tenantId, UUID companyId, UUID siteId) {
    public ContactScope {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(siteId, "siteId");
    }
}
