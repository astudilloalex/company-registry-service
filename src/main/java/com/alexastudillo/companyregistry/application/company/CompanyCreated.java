package com.alexastudillo.companyregistry.application.company;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompanyRecord;
import java.time.Instant;
import java.util.UUID;

/** Immutable accepted fact; legal enrichment is intentionally outside this event contract. */
public record CompanyCreated(UUID eventId, UUID tenantId, String aggregateType, UUID aggregateId, long aggregateVersion,
                             String eventType, short eventSchemaVersion, Instant occurredAt, String correlationId,
                             String causationId, Payload payload) {
    public record Payload(UUID companyId, UUID partyId, String code, String operatingName, String status,
                          Instant createdAt, String createdBy) {}
    public static CompanyCreated candidate(UUID eventId, CompanyRecord row, OperationContext context) {
        if (eventId.version()!=7 || eventId.equals(row.company().id()) || row.company().version().value()!=0
                || row.company().status()!=com.alexastudillo.companyregistry.domain.model.CompanyStatus.DRAFT
                || !context.tenantId().equals(row.company().tenantId()) || !context.actor().equals(row.audit().createdBy())) {
            throw new IllegalArgumentException("creation-event-invalid");
        }
        return new CompanyCreated(eventId,context.tenantId(),"COMPANY",row.company().id(),0,"company.created.v1",(short)1,
                row.audit().createdAt(),context.processId(),null,new Payload(row.company().id(),row.company().partyId(),row.code(),row.operatingName(),"DRAFT",row.audit().createdAt(),row.audit().createdBy()));
    }
}
