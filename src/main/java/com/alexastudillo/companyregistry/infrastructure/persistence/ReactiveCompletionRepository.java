package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.CompletionRepository;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ReactiveCompletionRepository implements CompletionRepository, PanacheRepositoryBase<CompletionEntity, CompletionId> {
    @Override public Uni<CompletionRecord> find(CompletionKey key) {
        return findById(new CompletionId(key)).map(row -> row == null ? null : row.value());
    }
    @Override public Uni<CompletionRecord> insert(CompletionRecord completion) {
        return persistAndFlush(CompletionEntity.from(completion)).map(CompletionEntity::value);
    }
}
