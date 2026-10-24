package com.alexastudillo.companyregistry.infrastructure.outbox;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/** The productive channel is lazy and excluded from business health. */
@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class OutboxBootTest {
    @Inject OutboxPolicy policy;
    @Inject Config config;
    @Inject OutboxScheduler scheduler;
    @Test void disabledBootsReadyWithoutBrokerCredentialsOrDelivery() {
        assertEquals(OutboxMode.DISABLED, policy.mode());
        assertEquals(Boolean.TRUE, config.getValue("mp.messaging.outgoing.company-outbox.lazy-client", Boolean.class));
        assertEquals(Boolean.FALSE, config.getValue("mp.messaging.outgoing.company-outbox.health-enabled", Boolean.class));
        assertNull(scheduler.poll().await().atMost(java.time.Duration.ofSeconds(1)));
        var response = given().get("/q/health/ready").then().statusCode(200).extract().asString();
        assertFalse(response.toLowerCase(java.util.Locale.ROOT).contains("rabbitmq"));
    }

    @Test void disabledExposesUnavailableBacklogBeforeDelivery() {
        var metrics = given().get("/q/metrics").then().statusCode(200).extract().asString();
        assertTrue(metrics.contains("company_outbox_pending NaN"));
        assertTrue(metrics.contains("company_outbox_oldest_age_seconds NaN"));
        assertTrue(metrics.contains("company_outbox_backlog_available 0.0"));
    }
}
