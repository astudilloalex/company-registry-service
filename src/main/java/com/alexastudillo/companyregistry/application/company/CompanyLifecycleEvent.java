package com.alexastudillo.companyregistry.application.company;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompanyRecord;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** The immutable activation or deactivation fact from its original acceptance. */
public record CompanyLifecycleEvent(UUID eventId, UUID tenantId, String aggregateType, UUID aggregateId,
        long aggregateVersion, String eventType, short eventSchemaVersion, Instant occurredAt,
        String correlationId, String causationId, CompanyUpdated.Payload payload) {
    private static final String INVALID_EVENT = "lifecycle-event-invalid";

    public CompanyLifecycleEvent {
        if (eventId == null || eventId.version() != 7 || eventId.variant() != 2 || eventId.equals(aggregateId)
                || tenantId == null || aggregateId == null || aggregateId.version() != 7
                || !"COMPANY".equals(aggregateType) || aggregateVersion <= 0 || eventSchemaVersion != 1
                || occurredAt == null || !occurredAt.equals(occurredAt.truncatedTo(ChronoUnit.MICROS))
                || correlationId == null || causationId != null || payload == null) {
            throw new IllegalArgumentException(INVALID_EVENT);
        }
        requirePayload(aggregateId, occurredAt, eventType, payload);
        if (!UUID.fromString(correlationId).toString().equalsIgnoreCase(correlationId)) {
            throw new IllegalArgumentException(INVALID_EVENT);
        }
    }

    private static void requirePayload(UUID aggregateId, Instant occurredAt, String eventType, CompanyUpdated.Payload payload) {
        String expected = switch (eventType) {
            case "company.activated.v1" -> "ACTIVE";
            case "company.deactivated.v1" -> "INACTIVE";
            default -> throw new IllegalArgumentException(INVALID_EVENT);
        };
        if (!expected.equals(payload.status()) || !aggregateId.equals(payload.companyId())
                || !occurredAt.equals(payload.updatedAt())) throw new IllegalArgumentException(INVALID_EVENT);
    }

    public static CompanyLifecycleEvent candidate(UUID eventId, CompanyRecord row, OperationContext context,
            CompanyLifecycle.Operation operation) {
        if (operation == CompanyLifecycle.Operation.ARCHIVE || row.company().status() != operation.target()
                || !context.tenantId().equals(row.company().tenantId())
                || !context.actor().equals(row.audit().updatedBy())) throw new IllegalArgumentException(INVALID_EVENT);
        return new CompanyLifecycleEvent(eventId, context.tenantId(), "COMPANY", row.company().id(),
                row.company().version().value(), operation == CompanyLifecycle.Operation.ACTIVATE
                        ? "company.activated.v1" : "company.deactivated.v1", (short) 1,
                row.audit().updatedAt(), context.processId(), null,
                new CompanyUpdated.Payload(row.company().id(), row.company().partyId(), row.code(), row.operatingName(),
                        row.company().status().name(), row.audit().updatedAt(), row.audit().updatedBy()));
    }
}
