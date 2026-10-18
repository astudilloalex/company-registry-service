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
public class SiteCreatedOutbox implements SiteCreationEvents {
    private final OutboxPolicy policy;

    @Inject
    public SiteCreatedOutbox(OutboxPolicy policy) {
        this.policy = policy;
    }

    @Override
    public Uni<OutboxRecord> store(SiteCreated candidate) {
        return policy.store(row(candidate));
    }

    public static OutboxRecord row(SiteCreated event) {
        if (!event.aggregateId().equals(event.payload().siteId())
                || event.eventId().equals(event.aggregateId())
                || !event.occurredAt().equals(event.payload().createdAt())) {
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
        payload.put("createdAt", p.createdAt().toString());
        payload.put("createdBy", p.createdBy());
        var audit = new Audit(event.occurredAt(), p.createdBy(), event.occurredAt(), p.createdBy());
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
