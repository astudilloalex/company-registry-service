package com.alexastudillo.companyregistry.contract;

import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import java.util.HashMap;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Only disposable infrastructure; never points to shared queues or the developer's database. */
public class DeliveryEnvironmentResource extends PostgresResource {
    public static GenericContainer<?> rabbit;
    public static Map<String, String> settings;
    public static ReferenceStubServer stub;

    @Override public Map<String, String> start() {
        var values = new HashMap<>(super.start());
        try {
            rabbit = new GenericContainer<>(DockerImageName.parse("rabbitmq:4.1.4-management-alpine"));
            rabbit.withEnv("RABBITMQ_DEFAULT_VHOST", "platform").withEnv("RABBITMQ_DEFAULT_USER", "delivery_test")
                    .withEnv("RABBITMQ_DEFAULT_PASS", "disposable_test").withExposedPorts(5672, 15672);
            rabbit.start();
            var user = rabbit.execInContainer("rabbitmqctl", "add_user", "publisher_test", "disposable_publisher");
            var permissions = rabbit.execInContainer("rabbitmqctl", "set_permissions", "-p", "platform", "publisher_test",
                    "^company\\.events$", "^company\\.events$", "^$");
            if (user.getExitCode() != 0 || permissions.getExitCode() != 0)
                throw new IllegalStateException("Cannot provision isolated publisher permissions");
            stub = new ReferenceStubServer();
            values.put("company-registry.outbox.mode", "published");
            values.put("company-registry.outbox.publisher.poll-interval", "100ms");
            values.put("company-registry.outbox.publisher.routing-key", "integration.%s");
            values.put("company-registry.outbox.broker.host", rabbit.getHost());
            values.put("company-registry.outbox.broker.port", rabbit.getMappedPort(5672).toString());
            values.put("company-registry.outbox.broker.username", "publisher_test");
            values.put("company-registry.outbox.broker.password", "disposable_publisher");
            values.put("mp.messaging.outgoing.company-outbox.host", rabbit.getHost());
            values.put("mp.messaging.outgoing.company-outbox.port", rabbit.getMappedPort(5672).toString());
            values.put("mp.messaging.outgoing.company-outbox.username", "publisher_test");
            values.put("mp.messaging.outgoing.company-outbox.password", "disposable_publisher");
            values.put("quarkus.rest-client.party-registry.url", stub.url());
            values.put("quarkus.rest-client.geographic-reference.url", stub.url());
            values.put("company-registry.idempotency.active-key-id", "delivery-test");
            values.put("company-registry.idempotency.keys.delivery-test", java.util.Base64.getEncoder().encodeToString(new byte[32]));
            settings = Map.copyOf(values);
            return values;
        } catch (Exception failure) { stop(); throw new IllegalStateException("Cannot provision isolated delivery fixtures", failure); }
    }

    @Override public void stop() {
        try { if (stub != null) stub.close(); }
        finally { try { if (rabbit != null) rabbit.stop(); } finally { super.stop(); } }
    }

    public static com.rabbitmq.client.Connection connection() throws Exception {
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(rabbit.getHost()); factory.setPort(rabbit.getMappedPort(5672));
        factory.setVirtualHost("platform"); factory.setUsername("delivery_test"); factory.setPassword("disposable_test");
        factory.setConnectionTimeout(1000); factory.setHandshakeTimeout(2000); factory.setAutomaticRecoveryEnabled(false);
        return factory.newConnection();
    }
}
