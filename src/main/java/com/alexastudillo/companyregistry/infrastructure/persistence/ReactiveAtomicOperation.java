package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.AtomicOperation;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.function.Supplier;

@ApplicationScoped
public class ReactiveAtomicOperation implements AtomicOperation {
    @Override public <T> Uni<T> execute(Supplier<Uni<T>> operation) {
        return Uni.createFrom().deferred(() -> Panache.withTransaction(operation))
                .onFailure(PersistenceFailures::technical).transform(PersistenceFailures::translate);
    }
    @Override public <T> Uni<T> read(Supplier<Uni<T>> operation) {
        return Uni.createFrom().deferred(() -> Panache.withSession(operation))
                .onFailure(PersistenceFailures::technical).transform(PersistenceFailures::translate);
    }
}
