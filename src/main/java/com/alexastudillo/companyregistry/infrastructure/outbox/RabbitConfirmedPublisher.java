package com.alexastudillo.companyregistry.infrastructure.outbox;

import com.alexastudillo.companyregistry.application.outbox.ConfirmedPublisher;
import com.alexastudillo.companyregistry.application.outbox.DeliveryFailure;
import com.alexastudillo.companyregistry.application.outbox.StoredEvent;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.ZoneOffset;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;

@ApplicationScoped
public class RabbitConfirmedPublisher implements ConfirmedPublisher {
    private final MutinyEmitter<byte[]> emitter;
    private final StoredEventSerializer serializer;
    private final String routingTemplate;
    private final int capacity;
    private final java.util.concurrent.atomic.AtomicInteger outstanding = new java.util.concurrent.atomic.AtomicInteger();

    @Inject
    public RabbitConfirmedPublisher(@Channel("company-outbox") MutinyEmitter<byte[]> emitter,
            StoredEventSerializer serializer, OutboxConfiguration configuration) {
        this.emitter = emitter; this.serializer = serializer;
        this.routingTemplate = configuration.publisher().routingKey();
        this.capacity = configuration.publisher().batchSize();
    }

    @Override public Uni<Void> publish(StoredEvent event) {
        return Uni.createFrom().deferred(() -> {
            byte[] body = serializer.serialize(event);
            if (!acquire()) return Uni.createFrom().failure(new DeliveryFailure(DeliveryFailure.Code.BROKER_UNAVAILABLE));
            var released = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable release = () -> { if (released.compareAndSet(false, true)) outstanding.decrementAndGet(); };
            var metadata = OutgoingRabbitMQMetadata.builder().withMessageId(event.eventId().toString())
                    .withContentType("application/json").withDeliveryMode(2).withType(event.kind().type())
                    .withTimestamp(event.kind().occurredAt().atZone(ZoneOffset.UTC))
                    .withCorrelationId(event.context().correlationId())
                    .withRoutingKey(OutboxDestination.route(routingTemplate, event.kind().type())).build();
            // sendMessage completes on ack/nack, not on enqueue. No application retry in this adapter.
            var message = Message.of(body, Metadata.of(metadata), () -> {
                release.run(); return java.util.concurrent.CompletableFuture.completedFuture(null);
            }, failure -> {
                release.run(); return java.util.concurrent.CompletableFuture.completedFuture(null);
            });
            return Uni.createFrom().deferred(() -> emitter.sendMessage(message)).onFailure().invoke(release)
                    .onFailure().transform(failure -> new DeliveryFailure(classify(failure)));
        });
    }

    private boolean acquire() {
        int current;
        do {
            current = outstanding.get();
            if (current >= capacity) return false;
        } while (!outstanding.compareAndSet(current, current + 1));
        return true;
    }

    private static DeliveryFailure.Code classify(Throwable failure) {
        // Vert.x 4.5.34 uses failedFuture with this fixed diagnostic for a broker nack.
        // Compare only; never propagate dependency messages or causes.
        return "Message publish nacked by the broker".equals(failure.getMessage()) ? DeliveryFailure.Code.BROKER_NACK
                : DeliveryFailure.Code.BROKER_UNAVAILABLE;
    }
}
