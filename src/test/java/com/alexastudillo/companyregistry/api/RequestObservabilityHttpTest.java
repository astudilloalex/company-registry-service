package com.alexastudillo.companyregistry.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alexastudillo.companyregistry.api.context.BusinessContextFilter;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

/**
 * Verifies reactive diagnostic isolation and low-cardinality operational
 * metrics over HTTP.
 */
@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class RequestObservabilityHttpTest {
    private static final String CONTEXT_PATH = "/v1/_verification/context";

    @Test
    void concurrentCompletionLogsRetainTheirOwnValidatedIdentity() throws Exception {
        var records = new ConcurrentHashMap<String, Map<String, String>>();
        var logger = Logger.getLogger(BusinessContextFilter.class.getName());
        var handler = capture(records);
        logger.addHandler(handler);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var expected = new ConcurrentHashMap<String, Map<String, String>>();
            var requests = new ArrayList<Future<?>>();
            for (int index = 0; index < 12; index++) {
                String process = UUID.randomUUID().toString();
                String tenant = UUID.randomUUID().toString();
                String actor = "observability-actor-" + index;
                expected.put(process, Map.of("processId", process, "tenantId", tenant, "userId", actor));
                requests.add(executor.submit(() -> given().header("Process-Id", process).header("Tenant-Id", tenant)
                        .header("User-Id", actor).get(CONTEXT_PATH).then().statusCode(200)));
            }
            for (var request : requests) {
                request.get(20, TimeUnit.SECONDS);
            }
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> records.keySet().containsAll(expected.keySet()));
            expected.forEach((process, identity) -> assertEquals(identity, records.get(process)));
            given().get(CONTEXT_PATH).then().statusCode(400);
            assertEquals(12, records.size());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void metricsExposeHttpAndReactivePoolWithoutIdentityLabels() {
        String process = UUID.randomUUID().toString();
        String tenant = UUID.randomUUID().toString();
        given().header("Process-Id", process).header("Tenant-Id", tenant).header("User-Id", "metric-actor")
                .get(CONTEXT_PATH).then().statusCode(200);
        String metrics = given().get("/q/metrics").then().statusCode(200).extract().asString();
        assertTrue(metrics.contains("http_server_requests"));
        assertTrue(metrics.contains("postgresql_queue_size"), "Reactive PostgreSQL pool metrics must be published");
        assertTrue(metrics.contains("company_outbox_pending"));
        assertFalse(metrics.contains(process));
        assertFalse(metrics.contains(tenant));
        assertFalse(metrics.contains("metric-actor"));
    }

    @Test
    void failedRequestsLogOnlyValidatedContextAndHealthRemainsIndependent() {
        var records = new ConcurrentHashMap<String, Map<String, String>>();
        var logger = Logger.getLogger(BusinessContextFilter.class.getName());
        var handler = capture(records);
        logger.addHandler(handler);
        try {
            String process = UUID.randomUUID().toString();
            given().header("Process-Id", process).header("Tenant-Id", "untrusted-tenant")
                    .header("User-Id", "untrusted-actor").get(CONTEXT_PATH).then().statusCode(400);
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> records.containsKey(process));
            assertNotNull(records.get(process));
            assertEquals(Map.of("processId", process), records.get(process));
            for (String endpoint : new String[] { "live", "ready", "started" }) {
                given().get("/q/health/" + endpoint).then().statusCode(200);
            }
            assertEquals(1, records.size());
        } finally {
            logger.removeHandler(handler);
        }
    }

    private static Handler capture(Map<String, Map<String, String>> records) {
        return new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                if (logRecord instanceof ExtLogRecord extended && logRecord.getMessage().startsWith("HTTP completed")) {
                    var identity = extended.getMdcCopy();
                    String process = identity.get("processId");
                    if (process != null) {
                        records.put(process, Map.copyOf(identity));
                    }
                }
            }

            @Override
            public void flush() {
                // In-memory test handler does not buffer log records.
            }

            @Override
            public void close() {
                // In-memory test handler holds no open resources.
            }
        };
    }
}
