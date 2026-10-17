package com.alexastudillo.companyregistry.infrastructure.outbox;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/** Broker independence is enforced by absent connectors/channels, not a live broker hidden behind mocks. */
@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class OutboxBootTest {
    @Inject OutboxPolicy policy;
    @Inject Config config;
    @Test void disabledBootsReadyWithoutConnectorChannelsSchedulerOrBrokerCredentials() {
        assertEquals(OutboxMode.DISABLED, policy.mode());
        assertNull(getClass().getClassLoader().getResource("io/smallrye/reactive/messaging/rabbitmq/RabbitMQConnector.class"));
        assertNull(getClass().getClassLoader().getResource("io/quarkus/scheduler/Scheduled.class"));
        for (var name : config.getPropertyNames()) {
            assertFalse(name.startsWith("mp.messaging.outgoing.") || name.startsWith("mp.messaging.incoming."), name);
        }
        var response = given().get("/q/health/ready").then().statusCode(200).extract().asString();
        assertFalse(response.toLowerCase(java.util.Locale.ROOT).contains("rabbitmq"));
    }
}
