package com.alexastudillo.companyregistry.application.outbox;

import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.concurrent.Executor;

/** One logical attempt per committed reservation; persistence failures never trigger a second write. */
public final class DeliveryCoordinator {
    public record Policy(int maximumAttempts, Duration timeout, Duration initialBackoff, Duration maximumBackoff) {
        public Duration backoff(int attempt) {
            long value = initialBackoff.toNanos();
            long maximum = maximumBackoff.toNanos();
            for (int i = 1; i < attempt && value < maximum; i++)
                value = value > maximum / 2 ? maximum : Math.min(maximum, value * 2);
            return Duration.ofNanos(value);
        }
    }
    private final DeliveryStore store;
    private final ConfirmedPublisher publisher;
    private final DeliveryObserver observer;
    private final Policy policy;
    private final Executor owner;

    public DeliveryCoordinator(DeliveryStore store, ConfirmedPublisher publisher, DeliveryObserver observer, Policy policy, Executor owner) {
        this.store = store; this.publisher = publisher; this.observer = observer; this.policy = policy;
        this.owner = owner;
    }

    public Uni<Void> deliver(DeliveryStore.Claim claim) {
        return Uni.createFrom().deferred(() -> publisher.publish(claim.event()))
                .ifNoItem().after(policy.timeout()).failWith(() -> new DeliveryFailure(DeliveryFailure.Code.PUBLISH_TIMEOUT))
                .invoke(() -> observer.observe(claim, DeliveryObserver.Signal.CONFIRMED, null))
                .replaceWith((DeliveryFailure.Code) null)
                .onFailure().recoverWithItem(DeliveryCoordinator::classify)
                .emitOn(owner)
                .chain(error -> transition(claim, error))
                .onFailure().invoke(() -> observer.observe(claim, DeliveryObserver.Signal.TRANSITION_UNCERTAIN, null))
                .onFailure().recoverWithNull().replaceWithVoid();
    }

    private Uni<Void> transition(DeliveryStore.Claim claim, DeliveryFailure.Code error) {
        DeliveryStore.Outcome outcome;
        if (error == null) {
            outcome = DeliveryStore.Outcome.PUBLISHED;
        } else if (error.terminal() || claim.reservation().attempt() >= policy.maximumAttempts()) {
            outcome = DeliveryStore.Outcome.FAILED;
        } else {
            outcome = DeliveryStore.Outcome.RETRY;
        }
        return store.finish(claim.reservation(), outcome, policy.backoff(claim.reservation().attempt()), error)
                .invoke(changed -> observer.observe(claim, Boolean.TRUE.equals(changed)
                        ? DeliveryObserver.Signal.valueOf(outcome.name()) : DeliveryObserver.Signal.STALE, error))
                .replaceWithVoid();
    }

    private static DeliveryFailure.Code classify(Throwable failure) {
        return failure instanceof DeliveryFailure delivery ? delivery.code() : DeliveryFailure.Code.BROKER_UNAVAILABLE;
    }
}
