package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.OutboxRepository;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.OutboxRecord;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class ReactiveOutboxRepository implements OutboxRepository, PanacheRepositoryBase<OutboxEntity, UUID> {
    @Override public Uni<OutboxRecord> find(UUID tenantId, UUID eventId) {
        return find("tenantId = ?1 and id = ?2", tenantId, eventId).firstResult().map(row -> row == null ? null : row.value());
    }
    @Override public Uni<OutboxRecord> insert(OutboxRecord event) {
        return persistAndFlush(OutboxEntity.from(event)).map(OutboxEntity::value);
    }
}
