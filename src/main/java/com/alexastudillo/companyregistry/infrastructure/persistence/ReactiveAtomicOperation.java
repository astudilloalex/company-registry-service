package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.AtomicOperation;
import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@ApplicationScoped
public class ReactiveAtomicOperation implements AtomicOperation {
    private static final Duration TIMER_GRANULARITY = Duration.ofMillis(1);
    private static final Duration MAX_CLEANUP_RESERVE = Duration.ofMillis(100);
    private static final Duration CANCELLATION_WAIT = Duration.ofSeconds(10);

    @Override public <T> Uni<T> execute(Duration timeout, Supplier<Uni<T>> operation) {
        return transaction(timeout, () -> checkpoint(timeout).chain(operation::get));
    }

    @Override public <T> Uni<T> read(Duration timeout, Supplier<Uni<T>> operation) {
        // Transaction-local server settings cannot leak back into the connection pool.
        return execute(timeout, operation);
    }

    @Override public Uni<Void> checkpoint(Duration remaining) {
        if (remaining.isNegative() || remaining.isZero()) return Uni.createFrom().failure(unavailable());
        var timeout = Math.max(1, remaining.toMillis()) + "ms";
        return Panache.getSession().chain(session -> session.createNativeQuery(
                "select set_config('statement_timeout', :timeout, true), set_config('lock_timeout', :timeout, true)")
                .setParameter("timeout", timeout).getSingleResult()).replaceWithVoid();
    }

    @Override public <T> Uni<T> execute(Supplier<Uni<T>> operation) {
        return transaction(null, operation);
    }

    private <T> Uni<T> transaction(Duration timeout, Supplier<Uni<T>> operation) {
        return Uni.createFrom().deferred(() -> {
            long started = System.nanoTime();
            var owner = contextExecutor(Vertx.currentContext());
            var cancelled = new CompletableFuture<T>();
            var finalized = new CompletableFuture<Void>();
            var transaction = Panache.withTransaction(() -> {
                var work = Uni.combine().any().of(operation.get(), Uni.createFrom().completionStage(cancelled));
                // Fail inside the transaction while rollback still has time. The outer
                // deadline also covers connection acquisition, commit and stalled cleanup.
                return timeout == null ? work : work.ifNoItem().after(workRemaining(timeout, started))
                        .failWith(ReactiveAtomicOperation::unavailable).emitOn(owner);
            })
                    .onTermination().invoke(() -> finalized.complete(null))
                    .onCancellation().call(() -> cancel(cancelled, finalized, owner,
                            timeout == null ? CANCELLATION_WAIT : timeout, started));
            return timeout == null ? transaction : transaction.ifNoItem().after(remaining(timeout, started))
                    .failWith(PersistenceFailure::finalizationUncertain).emitOn(owner);
        }).onFailure(PersistenceFailures::technical).transform(PersistenceFailures::translate);
    }

    private static Duration workRemaining(Duration timeout, long started) {
        long reserve = Math.min(timeout.toNanos() / 10, MAX_CLEANUP_RESERVE.toNanos());
        return remaining(timeout.minusNanos(reserve), started);
    }

    private static Duration remaining(Duration timeout, long started) {
        return Duration.ofNanos(Math.max(TIMER_GRANULARITY.toNanos(),
                timeout.toNanos() - (System.nanoTime() - started)));
    }

    private static Uni<Void> cancel(CompletableFuture<?> cancelled, CompletableFuture<Void> finalized,
            Executor owner, Duration timeout, long started) {
        // Only the cancellation signal is subscribed here, never another write.
        return Uni.createFrom().voidItem().emitOn(owner)
                .invoke(() -> cancelled.completeExceptionally(new CancellationException()))
                .chain(() -> {
                    if (System.nanoTime() - started >= timeout.toNanos()) return Uni.createFrom().voidItem();
                    return Uni.createFrom().completionStage(finalized).ifNoItem().after(remaining(timeout, started))
                            .recoverWithItem((Void) null);
                }).emitOn(owner);
    }

    private static Executor contextExecutor(Context context) {
        return action -> {
            if (context == null || context == Vertx.currentContext()) action.run();
            else context.runOnContext(_ -> action.run());
        };
    }

    private static PersistenceFailure unavailable() {
        return new PersistenceFailure(PersistenceFailure.Kind.UNAVAILABLE, null);
    }

    @Override public <T> Uni<T> read(Supplier<Uni<T>> operation) {
        return Uni.createFrom().deferred(() -> Panache.withSession(operation))
                .onFailure(PersistenceFailures::technical).transform(PersistenceFailures::translate);
    }
}
