package com.alexastudillo.companyregistry.contract;

import com.alexastudillo.companyregistry.application.outbox.StoredEvent;
import com.alexastudillo.companyregistry.application.persistence.RegistryData;
import com.alexastudillo.companyregistry.domain.model.AggregateType;
import com.alexastudillo.companyregistry.domain.model.OutboxStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Contract resources, not mutable production aggregates, supply wire-test facts. */
public final class OutboxFixtures {
    private OutboxFixtures() {}
    public static StoredEvent example(String name) {
        var json = new ObjectMapper();
        try (var input = OutboxFixtures.class.getResourceAsStream("/META-INF/contracts/events/" + name + ".example.json")) {
            var root = json.readTree(input);
            return new StoredEvent(UUID.fromString(root.path("eventId").asText()), UUID.fromString(root.path("tenantId").asText()),
                    new StoredEvent.Aggregate(AggregateType.valueOf(root.path("aggregateType").asText()),
                            UUID.fromString(root.path("aggregateId").asText()), root.path("aggregateVersion").asLong()),
                    new StoredEvent.Kind(root.path("eventType").asText(), (short) root.path("eventSchemaVersion").asInt(),
                            Instant.parse(root.path("occurredAt").asText())),
                    new StoredEvent.Context(root.path("correlationId").asText(), null),
                    json.convertValue(root.path("payload"), new TypeReference<Map<String, Object>>() {}));
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    public static RegistryData.OutboxRecord row(StoredEvent e) {
        var time = e.kind().occurredAt();
        return new RegistryData.OutboxRecord(e.eventId(), e.tenantId(), e.aggregate().type(), e.aggregate().id(),
                e.aggregate().version(), e.kind().type(), e.kind().schemaVersion(), e.payload(), time,
                e.context().correlationId(), e.context().causationId(), OutboxStatus.PENDING, 0, null, null, null,
                null, null, new RegistryData.Audit(time, "contract-test", time, "contract-test"), 0);
    }
}
