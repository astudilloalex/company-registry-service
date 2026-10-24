package com.alexastudillo.companyregistry.infrastructure.outbox;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(StoredOnlyOutboxMetricsBootTest.Profile.class)
@QuarkusTestResource(value = PostgresResource.class, restrictToAnnotatedClass = true)
class StoredOnlyOutboxMetricsBootTest {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("company-registry.outbox.mode", "stored-only");
        }
    }

    @Inject OutboxPolicy policy;

    @Test void storedOnlyExposesUnavailableBacklogBeforeDelivery() {
        assertEquals(OutboxMode.STORED_ONLY, policy.mode());
        var metrics = given().get("/q/metrics").then().statusCode(200).extract().asString();
        assertTrue(metrics.contains("company_outbox_pending NaN"));
        assertTrue(metrics.contains("company_outbox_oldest_age_seconds NaN"));
        assertTrue(metrics.contains("company_outbox_backlog_available 0.0"));
    }
}
