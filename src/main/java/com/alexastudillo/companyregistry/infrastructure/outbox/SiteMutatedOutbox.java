package com.alexastudillo.companyregistry.infrastructure.outbox;

import com.alexastudillo.companyregistry.application.site.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.domain.model.*;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class SiteMutatedOutbox implements SiteMutationEvents {
    private final OutboxPolicy policy;

    @Inject
    public SiteMutatedOutbox(OutboxPolicy policy) {
        this.policy = policy;
    }

    @Override
    public Uni<OutboxRecord> store(SiteMutated candidate) {
        return policy.store(row(candidate));
    }

    public static OutboxRecord row(SiteMutated event) {
        if (!event.aggregateId().equals(event.payload().siteId())
                || event.eventId().equals(event.aggregateId())
                || !event.occurredAt().equals(event.payload().updatedAt())
                || event.eventId().version() != 7 || event.aggregateVersion() <= 0
                || event.eventSchemaVersion() != 1 || !"COMPANY_SITE".equals(event.aggregateType())
                || !java.util.Set.of("DRAFT", "ACTIVE", "INACTIVE", "CLOSED").contains(event.payload().status())
                || !("CLOSED".equals(event.payload().status()) ? "company.site.closed.v1" : "company.site.updated.v1").equals(event.eventType())
                || event.causationId() != null
                || !event.occurredAt().equals(event.occurredAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("site-event-invariants-invalid");
        }
        var payload = new LinkedHashMap<String, Object>();
        var p = event.payload();
        payload.put("siteId", p.siteId().toString());
        payload.put("companyId", p.companyId().toString());
        payload.put("code", p.code());
        payload.put("name", p.name());
        payload.put("siteType", p.siteType());
        payload.put("status", p.status());
        payload.put("updatedAt", p.updatedAt().toString());
        payload.put("updatedBy", p.updatedBy());
        var audit = new Audit(event.occurredAt(), p.updatedBy(), event.occurredAt(), p.updatedBy());
        return new OutboxRecord(event.eventId(), event.tenantId(), AggregateType.COMPANY_SITE, event.aggregateId(),
                event.aggregateVersion(), event.eventType(), event.eventSchemaVersion(),
                Map.copyOf(payload), event.occurredAt(), event.correlationId(), event.causationId(),
                OutboxStatus.PENDING, 0, null, null, null, null, null, audit, 0);
    }

    /** HU08 serializes this original fact, never current Site or Company. */
    public static Map<String, Object> envelope(OutboxRecord row) {
        var event = new LinkedHashMap<String, Object>();
        event.put("eventId", row.id().toString());
        event.put("tenantId", row.tenantId().toString());
        event.put("aggregateType", row.aggregateType().name());
        event.put("aggregateId", row.aggregateId().toString());
        event.put("aggregateVersion", row.aggregateVersion());
        event.put("eventType", row.eventType());
        event.put("eventSchemaVersion", row.schemaVersion());
        event.put("occurredAt", row.occurredAt().toString());
        event.put("correlationId", row.correlationId());
        event.put("causationId", row.causationId());
        event.put("payload", row.payload());
        return java.util.Collections.unmodifiableMap(event);
    }
}
