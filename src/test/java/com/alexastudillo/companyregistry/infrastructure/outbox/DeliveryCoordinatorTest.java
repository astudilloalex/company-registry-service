package com.alexastudillo.companyregistry.infrastructure.outbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import com.alexastudillo.companyregistry.application.outbox.*;
import com.alexastudillo.companyregistry.contract.OutboxFixtures;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class DeliveryCoordinatorTest {
    private final DeliveryStore store = mock(DeliveryStore.class);
    private final ConfirmedPublisher publisher = mock(ConfirmedPublisher.class);
    private final ArrayList<DeliveryObserver.Signal> signals = new ArrayList<>();
    private final DeliveryCoordinator.Policy policy = new DeliveryCoordinator.Policy(3, Duration.ofMillis(25),
            Duration.ofSeconds(1), Duration.ofMinutes(5));
    private final DeliveryCoordinator coordinator = new DeliveryCoordinator(store, publisher,
            (claim, signal, code) -> signals.add(signal), policy, Runnable::run);

    private DeliveryStore.Claim claim(int attempt) {
        var event = OutboxFixtures.example("company.created.v1");
        return new DeliveryStore.Claim(event, new DeliveryStore.Reservation(event.eventId(), event.tenantId(), attempt,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(30), attempt));
    }

    @Test void waitsForConfirmationAndPersistsOnePublishedTransition() {
        var c = claim(1);
        var confirmed = new java.util.concurrent.CompletableFuture<Void>();
        when(publisher.publish(c.event())).thenReturn(Uni.createFrom().completionStage(confirmed));
        when(store.finish(c.reservation(), DeliveryStore.Outcome.PUBLISHED, policy.backoff(1), null)).thenReturn(Uni.createFrom().item(true));
        // Use a long timeout so this test checks the acknowledgement boundary, without a timing race.
        var slow = new DeliveryCoordinator(store, publisher, (claim, signal, code) -> signals.add(signal),
                new DeliveryCoordinator.Policy(3, Duration.ofSeconds(10), policy.initialBackoff(), policy.maximumBackoff()), Runnable::run);
        var result = slow.deliver(c).subscribeAsCompletionStage();
        verifyNoInteractions(store);
        assertFalse(result.toCompletableFuture().isDone());
        confirmed.complete(null);
        result.toCompletableFuture().join();
        verify(store).finish(c.reservation(), DeliveryStore.Outcome.PUBLISHED, policy.backoff(1), null);
        assertEquals(java.util.List.of(DeliveryObserver.Signal.CONFIRMED, DeliveryObserver.Signal.PUBLISHED), signals);
    }

    @Test void classifiesTimeoutNackTerminalAndAttemptExhaustion() {
        for (var code : new DeliveryFailure.Code[]{DeliveryFailure.Code.PUBLISH_TIMEOUT, DeliveryFailure.Code.BROKER_NACK,
                DeliveryFailure.Code.INVALID_CONTRACT}) {
            for (int attempt : new int[]{1, 3}) {
                var c = claim(attempt);
                var outcome = code.terminal() || attempt == 3 ? DeliveryStore.Outcome.FAILED : DeliveryStore.Outcome.RETRY;
                when(publisher.publish(c.event())).thenReturn(code == DeliveryFailure.Code.PUBLISH_TIMEOUT
                        ? Uni.createFrom().nothing() : Uni.createFrom().failure(new DeliveryFailure(code)));
                when(store.finish(c.reservation(), outcome, policy.backoff(attempt), code)).thenReturn(Uni.createFrom().item(true));
                coordinator.deliver(c).await().atMost(Duration.ofSeconds(1));
                verify(store).finish(c.reservation(), outcome, policy.backoff(attempt), code);
                assertTrue(signals.contains(DeliveryObserver.Signal.valueOf(outcome.name())));
            }
        }
    }

    @Test void uncertainPersistenceDoesNotTriggerAnotherWriteAndStaleResultIsDiscarded() {
        var c = claim(1);
        when(publisher.publish(c.event())).thenReturn(Uni.createFrom().voidItem());
        when(store.finish(c.reservation(), DeliveryStore.Outcome.PUBLISHED, policy.backoff(1), null))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("password=private")));
        coordinator.deliver(c).await().atMost(Duration.ofSeconds(1));
        verify(store).finish(c.reservation(), DeliveryStore.Outcome.PUBLISHED, policy.backoff(1), null);
        verifyNoMoreInteractions(store);
        assertEquals(java.util.List.of(DeliveryObserver.Signal.CONFIRMED, DeliveryObserver.Signal.TRANSITION_UNCERTAIN), signals);
        signals.clear();
        when(store.finish(c.reservation(), DeliveryStore.Outcome.PUBLISHED, policy.backoff(1), null)).thenReturn(Uni.createFrom().item(false));
        coordinator.deliver(c).await().atMost(Duration.ofSeconds(1));
        assertEquals(java.util.List.of(DeliveryObserver.Signal.CONFIRMED, DeliveryObserver.Signal.STALE), signals);
    }

    @Test void backoffSaturatesThroughTheMaximumAttemptWithoutOverflow() {
        assertEquals(Duration.ofSeconds(1), policy.backoff(1));
        assertEquals(Duration.ofSeconds(2), policy.backoff(2));
        assertEquals(Duration.ofMinutes(5), policy.backoff(100));
        var wide = new DeliveryCoordinator.Policy(100, Duration.ofSeconds(1), Duration.ofHours(23), Duration.ofHours(24));
        assertEquals(Duration.ofHours(24), wide.backoff(100));
    }
}
