package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.SiteRecord;
import java.time.Instant;
import java.util.UUID;

/** Immutable accepted fact; legal enrichment is intentionally outside this event contract. */
public record SiteMutated(UUID eventId, UUID tenantId, String aggregateType, UUID aggregateId, long aggregateVersion,
                             String eventType, short eventSchemaVersion, Instant occurredAt, String correlationId,
                             String causationId, Payload payload) {
    public record Payload(UUID siteId, UUID companyId, String code, String name, String siteType, String status,
                          Instant updatedAt, String updatedBy) {}
    public static SiteMutated candidate(UUID eventId, SiteRecord row, OperationContext context) {
        if (eventId.version()!=7 || eventId.equals(row.site().id()) || row.site().version().value()<=0
                || !context.tenantId().equals(row.site().tenantId()) || !context.actor().equals(row.audit().updatedBy())) {
            throw new IllegalArgumentException("site-mutation-event-invalid");
        }
        return new SiteMutated(eventId,context.tenantId(),"COMPANY_SITE",row.site().id(),row.site().version().value(), row.site().status()==com.alexastudillo.companyregistry.domain.model.SiteStatus.CLOSED ? "company.site.closed.v1" : "company.site.updated.v1",(short)1,
                row.audit().updatedAt(),context.processId(),null,new Payload(row.site().id(),row.site().companyId(),row.code(),row.name(),row.site().type().name(),row.site().status().name(),row.audit().updatedAt(),row.audit().updatedBy()));
    }
}
