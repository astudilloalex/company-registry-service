package com.alexastudillo.companyregistry.infrastructure.outbox;

import com.alexastudillo.companyregistry.application.persistence.OutboxRepository;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.OutboxRecord;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Selects atomic event storage; callers supply the outer transaction and T00 never sends messages. */
@Startup @ApplicationScoped
public class OutboxPolicy {
    private final OutboxMode mode;
    private final OutboxRepository repository;
    @Inject public OutboxPolicy(OutboxConfiguration config, OutboxRepository repository) {
        OutboxConfiguration.validate(config);
        mode = OutboxMode.parse(config.mode()); this.repository = repository;
    }
    public OutboxMode mode() { return mode; }
    public Uni<OutboxRecord> store(OutboxRecord event) {
        return mode == OutboxMode.DISABLED ? Uni.createFrom().nullItem() : repository.insert(event);
    }
}
