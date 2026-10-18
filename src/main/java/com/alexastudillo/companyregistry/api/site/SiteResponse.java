package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.companyregistry.application.site.SiteRegistration;
import java.time.Instant;
import java.util.UUID;

public record SiteResponse(UUID siteId, UUID companyId, String code, String name, String siteType, String status,
        long version, boolean effectivelyOperational, Instant createdAt, String createdBy, Instant updatedAt,
        String updatedBy) {
    public static SiteResponse from(SiteRegistration.Result result) {
        var row = result.site();
        var site = row.site();
        var audit = row.audit();
        return new SiteResponse(site.id(), site.companyId(), row.code(), row.name(), site.type().name(),
                site.status().name(),
                site.version().value(), result.effectivelyOperational(), audit.createdAt(), audit.createdBy(),
                audit.updatedAt(), audit.updatedBy());
    }
}
