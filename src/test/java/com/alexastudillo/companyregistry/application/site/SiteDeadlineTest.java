package com.alexastudillo.companyregistry.application.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.AtomicOperation;
import com.alexastudillo.companyregistry.application.persistence.CompletionRepository;
import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure;
import com.alexastudillo.companyregistry.domain.model.FieldChange;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SiteDeadlineTest {
    private static final Duration BUDGET = Duration.ofMillis(100);
    private static final Duration OBSERVER_LIMIT = Duration.ofSeconds(2);

    @Test
    void boundsSessionAcquisitionAndCancelsTheOnlySubscription() {
        var cancellations = new AtomicInteger();
        var operation = new StubOperation() {
            @Override public <T> Uni<T> read(Supplier<Uni<T>> work) {
                return Uni.createFrom().<T>nothing().onCancellation().invoke(cancellations::incrementAndGet);
            }
        };
        var call = service(operation, null).execute(command());
        var awaiting = call.await();
        var failure = assertThrows(SiteFailure.class, () -> awaiting.atMost(OBSERVER_LIMIT));
        assertEquals(SiteFailure.Kind.UNAVAILABLE, failure.kind());
        assertEquals(1, cancellations.get());
    }

    @Test
    void subMillisecondBudgetStillProducesTheOwnedUnavailableFailure() {
        var operation = new StubOperation() {
            @Override public <T> Uni<T> read(Supplier<Uni<T>> work) { return Uni.createFrom().nothing(); }
        };
        var service = new RegisterSite(new RegisterSite.Storage(operation, null, null, null),
                new RegisterSite.Idempotency(null, null, null), null, null, Duration.ofNanos(1));
        var awaiting = service.execute(command()).await();
        var failure = assertThrows(SiteFailure.class, () -> awaiting.atMost(OBSERVER_LIMIT));
        assertEquals(SiteFailure.Kind.UNAVAILABLE, failure.kind());
    }

    @Test
    void boundsTheResponseEvenWhenCancellationCleanupDoesNotFinish() {
        var cleanup = new AtomicInteger();
        var operation = new StubOperation() {
            @Override public <T> Uni<T> read(Supplier<Uni<T>> work) {
                return Uni.createFrom().<T>nothing().onCancellation().call(() -> {
                    cleanup.incrementAndGet();
                    return Uni.createFrom().nothing();
                });
            }
        };
        var call = service(operation, null).execute(command());
        var awaiting = call.await();
        var failure = assertThrows(SiteFailure.class, () -> awaiting.atMost(OBSERVER_LIMIT));
        assertEquals(SiteFailure.Kind.UNAVAILABLE, failure.kind());
        assertEquals(1, cleanup.get());
    }

    @Test
    void doesNotRecoverInASessionWhoseFinalizationIsStillUncertain() {
        var reads = new AtomicInteger();
        var writes = new AtomicInteger();
        var completions = mock(CompletionRepository.class);
        when(completions.find(any())).thenReturn(Uni.createFrom().nullItem());
        var operation = new StubOperation() {
            @Override public <T> Uni<T> read(Supplier<Uni<T>> work) {
                reads.incrementAndGet();
                return work.get();
            }
            @Override public <T> Uni<T> execute(Supplier<Uni<T>> work) {
                writes.incrementAndGet();
                return Uni.createFrom().failure(PersistenceFailure.finalizationUncertain());
            }
        };
        var awaiting = service(operation, completions).execute(command()).await();
        var failure = assertThrows(SiteFailure.class, () -> awaiting.atMost(OBSERVER_LIMIT));
        assertEquals(SiteFailure.Kind.UNAVAILABLE, failure.kind());
        assertEquals(1, reads.get());
        assertEquals(1, writes.get());
    }

    @Test
    void boundsRecoveryWithoutRetryingTheWriteOrResettingTheDeadline() {
        var reads = new AtomicInteger();
        var writes = new AtomicInteger();
        var completions = mock(CompletionRepository.class);
        when(completions.find(any())).thenReturn(Uni.createFrom().nullItem());
        var operation = new StubOperation() {
            @Override public <T> Uni<T> read(Supplier<Uni<T>> work) {
                return reads.incrementAndGet() == 1 ? work.get() : Uni.createFrom().nothing();
            }
            @Override public <T> Uni<T> execute(Supplier<Uni<T>> work) {
                writes.incrementAndGet();
                return Uni.createFrom().failure(new PersistenceFailure(PersistenceFailure.Kind.UNAVAILABLE, null));
            }
        };
        var call = service(operation, completions).execute(command());
        var awaiting = call.await();
        var failure = assertThrows(SiteFailure.class, () -> awaiting.atMost(OBSERVER_LIMIT));
        assertEquals(SiteFailure.Kind.UNAVAILABLE, failure.kind());
        assertEquals(2, reads.get());
        assertEquals(1, writes.get());
    }

    private RegisterSite service(AtomicOperation operation, CompletionRepository completions) {
        return new RegisterSite(new RegisterSite.Storage(operation, null, null, null),
                new RegisterSite.Idempotency(completions, null, null), null, null, BUDGET);
    }

    private SiteRegistration.Command command() {
        return new SiteRegistration.Command(new OperationContext(UUID.randomUUID(), "Actor", UUID.randomUUID().toString()),
                "deadline", UUID.randomUUID(), "SITE", "SITE", FieldChange.absent());
    }

    private abstract static class StubOperation implements AtomicOperation {
        @Override public <T> Uni<T> execute(Supplier<Uni<T>> work) { return work.get(); }
        @Override public <T> Uni<T> read(Supplier<Uni<T>> work) { return work.get(); }
    }
}
