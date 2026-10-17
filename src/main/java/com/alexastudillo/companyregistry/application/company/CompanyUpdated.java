package com.alexastudillo.companyregistry.application.company;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompanyRecord;
import java.time.Instant;
import java.util.UUID;

/** Immutable accepted fact; legal enrichment is intentionally outside this event contract. */
public record CompanyUpdated(UUID eventId, UUID tenantId, String aggregateType, UUID aggregateId, long aggregateVersion,
                             String eventType, short eventSchemaVersion, Instant occurredAt, String correlationId,
                             String causationId, Payload payload) {
    public CompanyUpdated {
        if (eventId == null || eventId.version() != 7 || eventId.variant() != 2 || eventId.equals(aggregateId)
                || tenantId == null || aggregateId == null || !"COMPANY".equals(aggregateType) || aggregateVersion <= 0
                || !"company.updated.v1".equals(eventType) || eventSchemaVersion != 1 || occurredAt == null
                || !occurredAt.equals(occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS))
                || correlationId == null || correlationId.isBlank() || causationId != null || payload == null
                || !aggregateId.equals(payload.companyId()) || !occurredAt.equals(payload.updatedAt())) {
            throw new IllegalArgumentException("update-event-invalid");
        }
    }
    public record Payload(UUID companyId, UUID partyId, String code, String operatingName, String status,
                          Instant updatedAt, String updatedBy) {
        public Payload {
            java.util.Objects.requireNonNull(companyId); java.util.Objects.requireNonNull(partyId);
            java.util.Objects.requireNonNull(updatedAt);
            if (!java.util.Set.of("DRAFT", "ACTIVE", "INACTIVE", "ARCHIVED").contains(status)
                    || !java.util.Objects.equals(code, com.alexastudillo.companyregistry.domain.text.BusinessText.required(code, 64))
                    || !java.util.Objects.equals(operatingName, com.alexastudillo.companyregistry.domain.text.BusinessText.required(operatingName, 200))) {
                throw new IllegalArgumentException("update-event-invalid");
            }
            com.alexastudillo.companyregistry.domain.text.OpaqueValue.required(updatedBy, 128);
        }
    }
    public static CompanyUpdated candidate(UUID eventId, CompanyRecord row, OperationContext context) {
        if (eventId.version()!=7 || eventId.equals(row.company().id()) || row.company().version().value()<=0
                || !com.alexastudillo.companyregistry.domain.model.CompanyInformationPolicy.editable(row.company().status())
                || !context.tenantId().equals(row.company().tenantId()) || !context.actor().equals(row.audit().updatedBy())) {
            throw new IllegalArgumentException("update-event-invalid");
        }
        return new CompanyUpdated(eventId,context.tenantId(),"COMPANY",row.company().id(),row.company().version().value(),"company.updated.v1",(short)1,
                row.audit().updatedAt(),context.processId(),null,new Payload(row.company().id(),row.company().partyId(),row.code(),row.operatingName(),row.company().status().name(),row.audit().updatedAt(),row.audit().updatedBy()));
    }
}
