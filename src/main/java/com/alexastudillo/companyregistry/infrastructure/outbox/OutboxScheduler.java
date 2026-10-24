package com.alexastudillo.companyregistry.infrastructure.outbox;

import com.alexastudillo.companyregistry.application.outbox.ConfirmedPublisher;
import com.alexastudillo.companyregistry.application.outbox.DeliveryCoordinator;
import com.alexastudillo.companyregistry.application.outbox.DeliveryStore;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Startup
@ApplicationScoped
public class OutboxScheduler {
    private final OutboxConfiguration config;
    private final DeliveryStore store;
    private final ConfirmedPublisher publisher;
    private final DeliveryContexts contexts;
    private final OutboxTelemetry telemetry;

    @Inject public OutboxScheduler(OutboxConfiguration config, DeliveryStore store, ConfirmedPublisher publisher,
            DeliveryContexts contexts, OutboxTelemetry telemetry) {
        OutboxConfiguration.validate(config);
        OutboxConfiguration.validateBroker(config);
        this.config = config; this.store = store; this.publisher = publisher; this.contexts = contexts; this.telemetry = telemetry;
    }

    @Scheduled(every = "${company-registry.outbox.publisher.poll-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public Uni<Void> poll() {
        if (OutboxMode.parse(config.mode()) != OutboxMode.PUBLISHED) return Uni.createFrom().voidItem();
        var p = config.publisher();
        return contexts.isolated(() -> store.reserve(p.batchSize(), p.maximumAttempts(), p.claimLease()))
                .invoke(batch -> telemetry.exhausted(batch.exhausted()))
                .chain(batch -> Multi.createFrom().iterable(batch.claims())
                        .onItem().transformToUni(claim -> contexts.isolated(() -> coordinator().deliver(claim)))
                        .merge(p.batchSize()).collect().asList())
                .chain(() -> contexts.isolated(store::backlog))
                .invoke(telemetry::backlog).replaceWithVoid()
                .onFailure().invoke(telemetry::unavailable).onFailure().recoverWithNull();
    }

    private DeliveryCoordinator coordinator() {
        var p = config.publisher();
        return new DeliveryCoordinator(store, publisher, telemetry,
                new DeliveryCoordinator.Policy(p.maximumAttempts(), p.publishTimeout(), p.initialBackoff(), p.maximumBackoff()),
                DeliveryContexts.executor(Vertx.currentContext()));
    }
}
