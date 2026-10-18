package com.alexastudillo.companyregistry.application.site;

import java.util.Objects;
import java.util.UUID;

/** Complete ownership required by every address query and acceptance. */
public record AddressScope(UUID tenantId, UUID companyId, UUID siteId) {
    public AddressScope {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(siteId, "siteId");
    }
}
