package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.SiteRecord;
import java.time.Instant;
import java.util.UUID;

/** Immutable accepted fact; legal enrichment is intentionally outside this event contract. */
public record SiteCreated(UUID eventId, UUID tenantId, String aggregateType, UUID aggregateId, long aggregateVersion,
                             String eventType, short eventSchemaVersion, Instant occurredAt, String correlationId,
                             String causationId, Payload payload) {
    public record Payload(UUID siteId, UUID companyId, String code, String name, String siteType, String status,
                          Instant createdAt, String createdBy) {}
    public static SiteCreated candidate(UUID eventId, SiteRecord row, OperationContext context) {
        if (eventId.version()!=7 || eventId.equals(row.site().id()) || row.site().version().value()!=0
                || row.site().status()!=com.alexastudillo.companyregistry.domain.model.SiteStatus.DRAFT
                || !context.tenantId().equals(row.site().tenantId()) || !context.actor().equals(row.audit().createdBy())) {
            throw new IllegalArgumentException("creation-event-invalid");
        }
        return new SiteCreated(eventId,context.tenantId(),"COMPANY_SITE",row.site().id(),0,"company.site.created.v1",(short)1,
                row.audit().createdAt(),context.processId(),null,new Payload(row.site().id(),row.site().companyId(),row.code(),row.name(),row.site().type().name(),"DRAFT",row.audit().createdAt(),row.audit().createdBy()));
    }
}
