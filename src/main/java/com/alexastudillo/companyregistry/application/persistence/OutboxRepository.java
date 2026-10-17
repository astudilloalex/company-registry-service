package com.alexastudillo.companyregistry.application.persistence;

import io.smallrye.mutiny.Uni;
import java.util.UUID;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.OutboxRecord;

public interface OutboxRepository {
    Uni<OutboxRecord> find(UUID tenantId, UUID eventId);
    Uni<OutboxRecord> insert(OutboxRecord event);
}
