package com.alexastudillo.companyregistry.application.outbox;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.OutboxRecord;
import com.alexastudillo.companyregistry.domain.model.AggregateType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** The accepted fact, separate from all delivery bookkeeping. */
public record StoredEvent(UUID eventId, UUID tenantId, Aggregate aggregate, Kind kind,
                          Context context, Map<String, Object> payload) {
    public StoredEvent { payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload)); }
    public record Aggregate(AggregateType type, UUID id, long version) {}
    public record Kind(String type, short schemaVersion, Instant occurredAt) {}
    public record Context(String correlationId, String causationId) {}

    public static StoredEvent from(OutboxRecord row) {
        return new StoredEvent(row.id(), row.tenantId(),
                new Aggregate(row.aggregateType(), row.aggregateId(), row.aggregateVersion()),
                new Kind(row.eventType(), row.schemaVersion(), row.occurredAt()),
                new Context(row.correlationId(), row.causationId()), row.payload());
    }
}
