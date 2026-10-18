package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.AtomicOperation;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.function.Supplier;

@ApplicationScoped
public class ReactiveAtomicOperation implements AtomicOperation {
    @Override public <T> Uni<T> execute(java.time.Duration timeout,Supplier<Uni<T>> operation) {
        return execute(()->checkpoint(timeout).chain(operation::get));
    }
    @Override public <T> Uni<T> read(java.time.Duration timeout,Supplier<Uni<T>> operation) {
        // Transaction-local server settings cannot leak back into the connection pool.
        return execute(timeout,operation);
    }
    @Override public Uni<Void> checkpoint(java.time.Duration remaining) {
        if(remaining.isNegative() || remaining.isZero()) return Uni.createFrom().failure(new com.alexastudillo.companyregistry.application.persistence.PersistenceFailure(
                com.alexastudillo.companyregistry.application.persistence.PersistenceFailure.Kind.UNAVAILABLE,null));
        var timeout=Math.max(1,remaining.toMillis())+"ms";
        return Panache.getSession().chain(session->session.createNativeQuery("select set_config('statement_timeout', :timeout, true), set_config('lock_timeout', :timeout, true)")
                .setParameter("timeout",timeout).getSingleResult()).replaceWithVoid();
    }
    @Override public <T> Uni<T> execute(Supplier<Uni<T>> operation) {
        return Uni.createFrom().deferred(() -> {
            var cancelled = new java.util.concurrent.CompletableFuture<T>();
            var finalized = new java.util.concurrent.CompletableFuture<Void>();
            // Turn downstream cancellation into a failure inside the transaction. Panache's
            // outer cancellation hook otherwise closes the session before HR's async rollback.
            // Work still has a single subscription; the second Uni is only a cancellation signal.
            return Panache.withTransaction(() -> Uni.combine().any().of(operation.get(),
                            Uni.createFrom().completionStage(cancelled)))
                    .onTermination().invoke(() -> finalized.complete(null))
                    .onCancellation().call(() -> {
                        cancelled.completeExceptionally(new java.util.concurrent.CancellationException());
                        return Uni.createFrom().completionStage(finalized);
                    });
        })
                .onFailure(PersistenceFailures::technical).transform(PersistenceFailures::translate);
    }
    @Override public <T> Uni<T> read(Supplier<Uni<T>> operation) {
        return Uni.createFrom().deferred(() -> Panache.withSession(operation))
                .onFailure(PersistenceFailures::technical).transform(PersistenceFailures::translate);
    }
}
