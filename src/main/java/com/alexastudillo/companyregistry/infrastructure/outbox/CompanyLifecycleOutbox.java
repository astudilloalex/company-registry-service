package com.alexastudillo.companyregistry.infrastructure.outbox;

import com.alexastudillo.companyregistry.application.company.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.domain.model.*;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class CompanyLifecycleOutbox implements LifecycleEvents {
    private final OutboxPolicy policy;

    @Inject public CompanyLifecycleOutbox(OutboxPolicy policy) { this.policy = policy; }

    @Override public Uni<OutboxRecord> store(CompanyLifecycleEvent event) { return policy.store(row(event)); }
    @Override public Uni<OutboxRecord> store(CompanyUpdated event) { return policy.store(CompanyUpdatedOutbox.row(event)); }

    public static OutboxRecord row(CompanyLifecycleEvent event) {
        var p = event.payload();
        var payload = Map.<String, Object>of("companyId", p.companyId().toString(), "partyId", p.partyId().toString(),
                "code", p.code(), "operatingName", p.operatingName(), "status", p.status(),
                "updatedAt", p.updatedAt().toString(), "updatedBy", p.updatedBy());
        var audit = new Audit(event.occurredAt(), p.updatedBy(), event.occurredAt(), p.updatedBy());
        return new OutboxRecord(event.eventId(), event.tenantId(), AggregateType.COMPANY, event.aggregateId(),
                event.aggregateVersion(), event.eventType(), event.eventSchemaVersion(), payload, event.occurredAt(),
                event.correlationId(), event.causationId(), OutboxStatus.PENDING, 0, null, null, null, null, null, audit, 0);
    }
}
