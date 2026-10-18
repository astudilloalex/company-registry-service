package com.alexastudillo.companyregistry.api.site;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.*;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
@TestProfile(SiteConcurrencyHttpTest.Profile.class)
class SiteConcurrencyHttpTest {
    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("company-registry.outbox.mode", "stored-only",
                    "quarkus.rest-client.party-registry.url", "http://127.0.0.1:9",
                    "quarkus.rest-client.geographic-reference.url", "http://127.0.0.1:9");
        }
    }

    @Inject
    Flyway flyway;
    @io.quarkus.test.common.http.TestHTTPResource
    java.net.URI base;

    private Connection db() throws SQLException {
        return flyway.getConfiguration().getDataSource().getConnection();
    }

    private UUID parent(UUID tenant) throws Exception {
        try (var c = db();
                var s = c.createStatement();
                var r = s.executeQuery(
                        "INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES('"
                                + tenant + "',uuidv7(),'" + UUID.randomUUID().toString().toUpperCase()
                                + "','PARENT','Actor','Actor') RETURNING id")) {
            r.next();
            return r.getObject(1, UUID.class);
        }
    }

    private Response post(UUID tenant, UUID company, String key, String code, String type) {
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of("code", code, "name", "SEDE", "siteType", type));
            var request = java.net.http.HttpRequest.newBuilder(base.resolve("/v1/companies/" + company + "/sites"))
                    .timeout(Duration.ofSeconds(15)).header("Tenant-Id", tenant.toString())
                    .header("User-Id", "Actor-aB")
                    .header("Process-Id", UUID.randomUUID().toString()).header("Idempotency-Key", key)
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            return new io.restassured.builder.ResponseBuilder().setStatusCode(response.statusCode())
                    .setBody(response.body())
                    .setContentType("application/json").build();
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    private long scalar(String query) throws Exception {
        try (var c = db(); var s = c.createStatement(); var r = s.executeQuery(query)) {
            r.next();
            return r.getLong(1);
        }
    }

    private void blocked(int n) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> scalar("SELECT count(*) FROM pg_locks WHERE NOT granted") >= n);
    }

    private void counts(UUID tenant, int sites, int events) throws Exception {
        assertEquals(sites, scalar("SELECT count(*) FROM company_sites WHERE tenant_id='" + tenant + "'"));
        assertEquals(sites, scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant
                + "' AND operation='company.site.create.v1'"));
        assertEquals(events, scalar("SELECT count(*) FROM company_outbox_events WHERE tenant_id='" + tenant + "'"));
    }

    @Test
    void concurrentEquivalentDifferentIntentCodeAndHeadquartersHaveOneAcceptance() throws Exception {
        for (var scenario : List.of("equivalent", "intent", "code", "hq")) {
            var tenant = UUID.randomUUID();
            var company = parent(tenant);
            try (var holder = db(); var s = holder.createStatement()) {
                holder.setAutoCommit(false);
                s.execute("SELECT id FROM companies WHERE id='" + company + "' FOR UPDATE");
                var first = CompletableFuture.supplyAsync(() -> post(tenant, company, "one", " code ",
                        scenario.equals("hq") ? "HEADQUARTERS" : "BRANCH"));
                var second = CompletableFuture.supplyAsync(() -> post(tenant, company,
                        scenario.equals("equivalent") || scenario.equals("intent") ? "one" : "two",
                        scenario.equals("intent") || scenario.equals("hq") ? "OTHER" : "CODE",
                        scenario.equals("hq") ? "HEADQUARTERS" : "BRANCH"));
                blocked(2);
                holder.rollback();
                var a = first.get(15, TimeUnit.SECONDS);
                var b = second.get(15, TimeUnit.SECONDS);
                if (scenario.equals("equivalent")) {
                    assertEquals(201, a.statusCode(), a.asString());
                    assertEquals(201, b.statusCode(), b.asString());
                    assertEquals(a.jsonPath().getMap("data"), b.jsonPath().getMap("data"));
                } else {
                    assertEquals(Set.of(201, 409), Set.of(a.statusCode(), b.statusCode()));
                    var loser = a.statusCode() == 409 ? a : b;
                    assertEquals(switch (scenario) {
                        case "intent" -> "idempotency-key-already-used-with-different-request";
                        case "code" -> "site-code-already-exists";
                        default -> "headquarters-already-exists";
                    }, loser.jsonPath().getString("code"));
                    assertEquals(2, loser.jsonPath().getMap("").size());
                }
            }
            counts(tenant, 1, 1);
        }
    }

    @Test
    void crossCompanyKeyRaceRollsBackTheLosingSiteAndDoesNotExposeItsSnapshot() throws Exception {
        var tenant = UUID.randomUUID();
        var a = parent(tenant);
        var b = parent(tenant);
        try (var holder = db(); var s = holder.createStatement()) {
            holder.setAutoCommit(false);
            s.execute("LOCK TABLE company_idempotency_records IN SHARE MODE");
            var first = CompletableFuture.supplyAsync(() -> post(tenant, a, "shared", "CODE", "BRANCH"));
            var second = CompletableFuture.supplyAsync(() -> post(tenant, b, "shared", "CODE", "BRANCH"));
            blocked(2);
            holder.rollback();
            var x = first.get(15, TimeUnit.SECONDS);
            var y = second.get(15, TimeUnit.SECONDS);
            assertEquals(Set.of(201, 409), Set.of(x.statusCode(), y.statusCode()));
            var loser = x.statusCode() == 409 ? x : y;
            assertEquals("idempotency-key-already-used-with-different-request", loser.jsonPath().getString("code"));
            assertFalse(loser.jsonPath().getMap("").containsKey("data"));
        }
        counts(tenant, 1, 1);
    }

    @Test
    void rollbackOfTheLockHolderAllowsRegistrationAndArchivalSerializesEveryTypeInBothOrders() throws Exception {
        for (var type : List.of("HEADQUARTERS", "BRANCH", "WAREHOUSE", "OTHER")) {
            var tenant = UUID.randomUUID();
            var company = parent(tenant);
            // Archival owns Company first, then commits before admission is evaluated.
            try (var holder = db(); var s = holder.createStatement()) {
                holder.setAutoCommit(false);
                s.execute("UPDATE companies SET status='ARCHIVED' WHERE id='" + company + "'");
                var pending = CompletableFuture.supplyAsync(() -> post(tenant, company, "archived", "CODE", type));
                blocked(1);
                holder.commit();
                var result = pending.get(15, TimeUnit.SECONDS);
                assertEquals(409, result.statusCode());
                assertEquals("site-registration-not-allowed-for-archived-company", result.jsonPath().getString("code"));
            }
            counts(tenant, 0, 0);
            var second = parent(tenant);
            try (var holder = db(); var s = holder.createStatement()) {
                holder.setAutoCommit(false);
                s.execute("LOCK TABLE company_idempotency_records IN SHARE MODE");
                var registration = CompletableFuture.supplyAsync(() -> post(tenant, second, "first", "CODE", type));
                blocked(1); // Registration has Company locked and is waiting to insert its completion.
                var archival = CompletableFuture.runAsync(() -> {
                    try (var c = db(); var st = c.createStatement()) {
                        st.execute("UPDATE companies SET status='ARCHIVED' WHERE id='" + second + "'");
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                });
                blocked(2);
                holder.rollback();
                assertEquals(201, registration.get(15, TimeUnit.SECONDS).statusCode());
                archival.get(15, TimeUnit.SECONDS);
            }
            counts(tenant, 1, 1);
            // A provisional archival that rolls back must not consume the request key.
            var third = parent(tenant);
            try (var holder = db(); var s = holder.createStatement()) {
                holder.setAutoCommit(false);
                s.execute("UPDATE companies SET status='ARCHIVED' WHERE id='" + third + "'");
                var pending = CompletableFuture.supplyAsync(() -> post(tenant, third, "rollback-holder", "CODE", type));
                blocked(1);
                holder.rollback();
                assertEquals(201, pending.get(15, TimeUnit.SECONDS).statusCode());
            }
            counts(tenant, 2, 2);
        }
    }
}
