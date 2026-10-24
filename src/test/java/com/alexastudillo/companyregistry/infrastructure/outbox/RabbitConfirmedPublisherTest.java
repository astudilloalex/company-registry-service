package com.alexastudillo.companyregistry.infrastructure.outbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import com.alexastudillo.companyregistry.application.outbox.DeliveryFailure;
import com.alexastudillo.companyregistry.application.outbox.StoredEvent;
import com.alexastudillo.companyregistry.contract.OutboxFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import java.time.Duration;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class RabbitConfirmedPublisherTest {
    private static final Duration WAIT = Duration.ofSeconds(1);
    @org.mockito.Mock MutinyEmitter<byte[]> emitter;
    @Test void propagatesAcknowledgementAndMetadataWithoutCompletingAtEnqueue() {
        var ack = new java.util.concurrent.CompletableFuture<Void>();
        when(emitter.sendMessage(any())).thenAnswer(invocation -> {
            Message<?> message = invocation.getArgument(0);
            var metadata = message.getMetadata(OutgoingRabbitMQMetadata.class).orElseThrow();
            assertEquals("company.created.v1", metadata.getRoutingKey());
            assertEquals("application/json", metadata.getContentType());
            assertEquals(2, metadata.getDeliveryMode());
            return Uni.createFrom().completionStage(ack);
        });
        var sender = new RabbitConfirmedPublisher(emitter, new StoredEventSerializer(new ObjectMapper()), config());
        var fact = OutboxFixtures.example("company.created.v1");
        var result = sender.publish(fact).subscribeAsCompletionStage();
        assertFalse(result.toCompletableFuture().isDone());
        ack.complete(null);
        result.toCompletableFuture().join();
        verify(emitter).sendMessage(any());
    }

    @Test void classifiesConnectorNackAndSanitizesOtherFailures() {
        var sender = new RabbitConfirmedPublisher(emitter, new StoredEventSerializer(new ObjectMapper()), config());
        var fact = OutboxFixtures.example("company.created.v1");
        when(emitter.sendMessage(any())).thenReturn(Uni.createFrom().failure(
                new io.vertx.core.impl.NoStackTraceThrowable("Message publish nacked by the broker")));
        var nackResult = sender.publish(fact).await();
        var nack = assertThrows(DeliveryFailure.class, () -> nackResult.atMost(WAIT));
        assertEquals(DeliveryFailure.Code.BROKER_NACK, nack.code());
        when(emitter.sendMessage(any())).thenReturn(Uni.createFrom().failure(new RuntimeException("password=private-payload")));
        var unavailableResult = sender.publish(fact).await();
        var unavailable = assertThrows(DeliveryFailure.class, () -> unavailableResult.atMost(WAIT));
        assertEquals("broker-unavailable", unavailable.getMessage());
        assertNull(unavailable.getCause());
    }

    @Test void invalidContractNeverReachesEmitter() {
        var sender = new RabbitConfirmedPublisher(emitter, new StoredEventSerializer(new ObjectMapper()), config());
        var e = OutboxFixtures.example("company.created.v1");
        var payload = new java.util.HashMap<>(e.payload()); payload.put("email", "private");
        var invalid = new StoredEvent(e.eventId(), e.tenantId(), e.aggregate(), e.kind(), e.context(), payload);
        var result = sender.publish(invalid).await();
        var failure = assertThrows(DeliveryFailure.class, () -> result.atMost(WAIT));
        assertEquals(DeliveryFailure.Code.INVALID_CONTRACT, failure.code());
        verifyNoInteractions(emitter);
    }
    private static OutboxConfiguration config() {
        return new io.smallrye.config.SmallRyeConfigBuilder().withMapping(OutboxConfiguration.class)
                .withConverter(Duration.class, 100, new io.quarkus.runtime.configuration.DurationConverter())
                .build().getConfigMapping(OutboxConfiguration.class);
    }

    @Test void timedOutTransportRetainsCapacityUntilPhysicalAckAndDoesNotGrowBuffers() {
        var configuration = new io.smallrye.config.SmallRyeConfigBuilder().withMapping(OutboxConfiguration.class)
                .withConverter(Duration.class, 100, new io.quarkus.runtime.configuration.DurationConverter())
                .withDefaultValue("company-registry.outbox.publisher.batch-size", "1")
                .build().getConfigMapping(OutboxConfiguration.class);
        var captured = new java.util.concurrent.atomic.AtomicReference<Message<?>>();
        when(emitter.sendMessage(any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0)); return Uni.createFrom().nothing();
        });
        var sender = new RabbitConfirmedPublisher(emitter, new StoredEventSerializer(new ObjectMapper()), configuration);
        var fact = OutboxFixtures.example("company.created.v1");
        var first = sender.publish(fact).subscribe().with(ignored -> {}, failure -> {});
        first.cancel();
        var result = sender.publish(fact).await();
        var failure = assertThrows(DeliveryFailure.class, () -> result.atMost(WAIT));
        assertEquals(DeliveryFailure.Code.BROKER_UNAVAILABLE, failure.code());
        verify(emitter, times(1)).sendMessage(any());
        captured.get().ack().toCompletableFuture().join();
        var second = sender.publish(fact).subscribe().with(ignored -> {}, ignored -> {});
        verify(emitter, times(2)).sendMessage(any());
        second.cancel();
    }
}
