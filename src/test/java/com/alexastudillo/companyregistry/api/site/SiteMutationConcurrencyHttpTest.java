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
@TestProfile(SiteMutationConcurrencyHttpTest.Profile.class)
class SiteMutationConcurrencyHttpTest {
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
        // Leave finalization time within the application's ten-second request budget.
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8))
                .until(() -> scalar("SELECT count(*) FROM pg_locks WHERE NOT granted") >= n);
    }

    private UUID site(UUID tenant, UUID company, String code) throws Exception {
        try (var c = db(); var s = c.createStatement(); var r = s.executeQuery(
                "INSERT INTO company_sites(tenant_id,company_id,code,name,created_by,updated_by) VALUES('" + tenant + "','" + company
                        + "','" + code + "','SEDE','Creator','Creator') RETURNING id")) {
            r.next(); return r.getObject(1, UUID.class);
        }
    }

    private Response mutate(UUID tenant, UUID company, UUID site, String key, String body, String action) {
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var suffix = action.equals("update") ? "" : "/" + action;
            var request = java.net.http.HttpRequest.newBuilder(base.resolve("/v1/companies/" + company + "/sites/" + site + suffix))
                    .timeout(Duration.ofSeconds(15)).header("Tenant-Id", tenant.toString()).header("User-Id", "Actor-aB")
                    .header("Process-Id", UUID.randomUUID().toString()).header("If-Match", "0").header("Content-Type", "application/json");
            if (key != null) request.header("Idempotency-Key", key);
            var response = client.send(request.method(action.equals("update") ? "PATCH" : "POST",
                    java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            return new io.restassured.builder.ResponseBuilder().setStatusCode(response.statusCode()).setBody(response.body())
                    .setContentType("application/json").build();
        } catch (Exception failure) { throw new CompletionException(failure); }
    }

    private void effects(UUID tenant, long revisions, long completions, long events) throws Exception {
        assertEquals(revisions, scalar("SELECT sum(version) FROM company_sites WHERE tenant_id='" + tenant + "'"));
        assertEquals(completions, scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant + "'"));
        assertEquals(events, scalar("SELECT count(*) FROM company_outbox_events WHERE tenant_id='" + tenant + "'"));
    }

    @Test void effectiveChangesAndNoOpsSerializeAgainstTheSameRevision() throws Exception {
        for (var scenario : List.of("effective", "noop-first", "change-first")) {
            var tenant = UUID.randomUUID(); var company = parent(tenant); var site = site(tenant, company, "A");
            try (var holder = db(); var s = holder.createStatement()) {
                holder.setAutoCommit(false); s.execute("SELECT id FROM companies WHERE id='" + company + "' FOR UPDATE");
                String firstBody = scenario.equals("noop-first") ? "{}" : "{\"name\":\"FIRST\"}";
                String secondBody = scenario.equals("change-first") ? "{}" : "{\"name\":\"SECOND\"}";
                var first = CompletableFuture.supplyAsync(() -> mutate(tenant, company, site, "one", firstBody, "update"));
                blocked(1);
                var second = CompletableFuture.supplyAsync(() -> mutate(tenant, company, site, "two", secondBody, "update"));
                blocked(2); holder.rollback();
                var a = first.get(15, TimeUnit.SECONDS); var b = second.get(15, TimeUnit.SECONDS);
                assertEquals(200, a.statusCode(), a.asString());
                assertEquals(scenario.equals("noop-first") ? 200 : 412, b.statusCode(), b.asString());
                if (scenario.equals("noop-first")) assertEquals(0, a.jsonPath().getInt("data.version"));
                else assertEquals("aggregate-version-mismatch", b.jsonPath().getString("code"));
            }
            effects(tenant, 1, scenario.equals("noop-first") ? 2 : 1, 1);
        }
    }

    @Test void sameKeySerializesEquivalentAndDifferentIntentBeforeCurrentRevision() throws Exception {
        for (boolean equivalent : List.of(true, false)) {
            var tenant = UUID.randomUUID(); var company = parent(tenant); var site = site(tenant, company, "A");
            try (var holder = db(); var s = holder.createStatement()) {
                holder.setAutoCommit(false); s.execute("SELECT id FROM companies WHERE id='" + company + "' FOR UPDATE");
                var first = CompletableFuture.supplyAsync(() -> mutate(tenant, company, site, "same", "{\"name\":\"NAME\"}", "update"));
                var second = CompletableFuture.supplyAsync(() -> mutate(tenant, company, site, "same", equivalent ? "{\"name\":\" name \"}" : "{\"name\":\"OTHER\"}", "update"));
                blocked(2); holder.rollback();
                var a = first.get(15, TimeUnit.SECONDS); var b = second.get(15, TimeUnit.SECONDS);
                if (equivalent) {
                    assertEquals(200, a.statusCode(), a.asString()); assertEquals(200, b.statusCode(), b.asString());
                    assertEquals(a.jsonPath().getMap("data"), b.jsonPath().getMap("data"));
                } else {
                    assertEquals(Set.of(200,409), Set.of(a.statusCode(), b.statusCode()));
                    assertEquals("idempotency-key-already-used-with-different-request", (a.statusCode()==409 ? a : b).jsonPath().getString("code"));
                }
            }
            effects(tenant, 1, 1, 1);
        }
    }

    @Test void crossCompanyKeyCollisionRollsBackTheLoserAndExposesNoForeignSnapshot() throws Exception {
        var tenant = UUID.randomUUID(); var a = parent(tenant); var b = parent(tenant);
        var sa = site(tenant, a, "A"); var sb = site(tenant, b, "B");
        try (var holder = db(); var s = holder.createStatement()) {
            holder.setAutoCommit(false); s.execute("LOCK TABLE company_idempotency_records IN SHARE MODE");
            var first = CompletableFuture.supplyAsync(() -> mutate(tenant, a, sa, "shared", "{\"name\":\"FIRST\"}", "update"));
            var second = CompletableFuture.supplyAsync(() -> mutate(tenant, b, sb, "shared", "{\"name\":\"SECOND\"}", "update"));
            blocked(2); holder.rollback();
            var x = first.get(15, TimeUnit.SECONDS); var y = second.get(15, TimeUnit.SECONDS);
            assertEquals(Set.of(200,409), Set.of(x.statusCode(), y.statusCode()));
            var loser = x.statusCode()==409 ? x : y;
            assertEquals("idempotency-key-already-used-with-different-request", loser.jsonPath().getString("code"));
            assertFalse(loser.jsonPath().getMap("").containsKey("data"));
        }
        effects(tenant, 1, 1, 1);
    }

    @Test void codeAndHeadquartersAcquisitionSerializeIncludingHu04Registration() throws Exception {
        for (var scenario : List.of("code", "headquarters", "create-code", "create-headquarters")) {
            var tenant = UUID.randomUUID(); var company = parent(tenant);
            var a = site(tenant, company, "A"); var b = site(tenant, company, "B");
            boolean hq = scenario.contains("headquarters");
            String body = hq ? "{\"siteType\":\"HEADQUARTERS\"}" : "{\"code\":\"FREE\"}";
            try (var holder = db(); var s = holder.createStatement()) {
                holder.setAutoCommit(false); s.execute("SELECT id FROM companies WHERE id='" + company + "' FOR UPDATE");
                var first = CompletableFuture.supplyAsync(() -> mutate(tenant, company, a, "first", body, "update"));
                var second = CompletableFuture.supplyAsync(() -> scenario.startsWith("create")
                        ? post(tenant, company, "second", hq ? "NEW" : "FREE", hq ? "HEADQUARTERS" : "BRANCH")
                        : mutate(tenant, company, b, "second", body, "update"));
                blocked(2); holder.rollback();
                var x = first.get(15, TimeUnit.SECONDS); var y = second.get(15, TimeUnit.SECONDS);
                assertEquals(1, (x.statusCode()==409 ? 1 : 0)+(y.statusCode()==409 ? 1 : 0));
                var loser = x.statusCode()==409 ? x : y;
                assertEquals(hq ? "headquarters-already-exists" : "site-code-already-exists", loser.jsonPath().getString("code"));
                assertTrue(x.statusCode()==200 || y.statusCode()==200 || y.statusCode()==201);
            }
            assertEquals(1, scalar("SELECT count(*) FROM company_sites WHERE tenant_id='" + tenant + "' AND "
                    + (hq ? "site_type='HEADQUARTERS' AND status<>'CLOSED'" : "code='FREE'")));
            assertEquals(1, scalar("SELECT count(*) FROM company_outbox_events WHERE tenant_id='" + tenant + "'"));
        }
    }
    private void sql(String text) throws Exception {
        try (var connection = db(); var statement = connection.createStatement()) { statement.execute(text); }
    }

    /** Test-only compatible local Company writer, no HU03 endpoint implied. */
    private boolean companyWriter(UUID tenant, UUID company, UUID site, boolean activate) {
        try (var connection = db(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT id FROM companies WHERE tenant_id='" + tenant + "' AND id='" + company + "' FOR UPDATE");
            try (var rows = statement.executeQuery("SELECT status,site_type FROM company_sites WHERE tenant_id='" + tenant
                    + "' AND company_id='" + company + "' AND id='" + site + "' FOR UPDATE")) {
                assertTrue(rows.next());
                if (activate && (!rows.getString(1).equals("ACTIVE") || !rows.getString(2).equals("HEADQUARTERS"))) {
                    connection.rollback(); return false;
                }
            }
            statement.executeUpdate("UPDATE companies SET status='" + (activate ? "ACTIVE" : "ARCHIVED")
                    + "',version=version+1,updated_at=clock_timestamp(),updated_by='TestWriter' WHERE id='" + company + "'");
            connection.commit(); return true;
        } catch (Exception failure) { throw new CompletionException(failure); }
    }

    @Test void archiveAndEveryMutationSerializeInBothOrders() throws Exception {
        for (var action : List.of("update", "noop", "activate", "deactivate", "close")) {
            for (boolean writerFirst : List.of(true, false)) {
                var tenant = UUID.randomUUID(); var company = parent(tenant); var site = site(tenant, company, "A");
                if (action.equals("deactivate")) sql("UPDATE company_sites SET status='ACTIVE' WHERE id='" + site + "'");
                String operation = action.equals("noop") ? "update" : action;
                String body = action.equals("update") ? "{\"name\":\"NEW\"}" : "{}";
                try (var holder = db(); var statement = holder.createStatement()) {
                    holder.setAutoCommit(false);
                    if (writerFirst) {
                        statement.executeUpdate("UPDATE companies SET status='ARCHIVED' WHERE id='" + company + "'");
                        var mutation = CompletableFuture.supplyAsync(() -> mutate(tenant, company, site, "key", body, operation));
                        blocked(1); holder.commit();
                        var result = mutation.get(15, TimeUnit.SECONDS);
                        assertEquals(409, result.statusCode(), result.asString());
                        assertEquals("site-mutation-not-allowed-for-archived-company", result.jsonPath().getString("code"));
                        effects(tenant, 0, 0, 0);
                    } else {
                        statement.execute("SELECT id FROM company_sites WHERE id='" + site + "' FOR UPDATE");
                        var mutation = CompletableFuture.supplyAsync(() -> mutate(tenant, company, site, "key", body, operation));
                        blocked(1);
                        var archive = CompletableFuture.supplyAsync(() -> companyWriter(tenant, company, site, false));
                        blocked(2); holder.rollback();
                        var result = mutation.get(15, TimeUnit.SECONDS);
                        assertEquals(200, result.statusCode(), result.asString());
                        assertTrue(archive.get(15, TimeUnit.SECONDS));
                        effects(tenant, action.equals("noop") ? 0 : 1, 1, action.equals("noop") ? 0 : 1);
                    }
                }
            }
        }
    }

    @Test void activationAndWithdrawalOfLegalHeadquartersPreserveTheInvariantInBothOrders() throws Exception {
        for (var action : List.of("update", "deactivate", "close")) {
            for (boolean writerFirst : List.of(true, false)) {
                var tenant = UUID.randomUUID(); var company = parent(tenant); var site = site(tenant, company, "A");
                sql("UPDATE companies SET status='INACTIVE' WHERE id='" + company + "'");
                sql("UPDATE company_sites SET status='ACTIVE',site_type='HEADQUARTERS' WHERE id='" + site + "'");
                String body = action.equals("update") ? "{\"siteType\":\"BRANCH\"}" : "{}";
                try (var holder = db(); var statement = holder.createStatement()) {
                    holder.setAutoCommit(false);
                    if (writerFirst) {
                        statement.execute("SELECT id FROM companies WHERE id='" + company + "' FOR UPDATE");
                        statement.execute("SELECT id FROM company_sites WHERE id='" + site + "' FOR UPDATE");
                        statement.executeUpdate("UPDATE companies SET status='ACTIVE' WHERE id='" + company + "'");
                        var mutation = CompletableFuture.supplyAsync(() -> mutate(tenant, company, site, "key", body, action));
                        blocked(1); holder.commit();
                        var result = mutation.get(15, TimeUnit.SECONDS);
                        assertEquals(409, result.statusCode(), result.asString());
                        assertEquals("active-headquarters-required", result.jsonPath().getString("code"));
                    } else {
                        statement.execute("SELECT id FROM company_sites WHERE id='" + site + "' FOR UPDATE");
                        var mutation = CompletableFuture.supplyAsync(() -> mutate(tenant, company, site, "key", body, action));
                        blocked(1);
                        var activate = CompletableFuture.supplyAsync(() -> companyWriter(tenant, company, site, true));
                        blocked(2); holder.rollback();
                        var result = mutation.get(15, TimeUnit.SECONDS);
                        assertEquals(200, result.statusCode(), result.asString());
                        assertFalse(activate.get(15, TimeUnit.SECONDS));
                    }
                }
                assertEquals(0, scalar("SELECT count(*) FROM companies c WHERE c.id='" + company
                        + "' AND c.status='ACTIVE' AND (SELECT count(*) FROM company_sites s WHERE s.company_id=c.id AND s.status='ACTIVE' AND s.site_type='HEADQUARTERS')<>1"));
            }
        }
    }

    /** Test-only HU06/HU07 local writer, sharing Site revision and Company → Site order. */
    private boolean childWriter(Connection connection, UUID tenant, UUID company, UUID site, String kind) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("SELECT id FROM companies WHERE tenant_id='" + tenant + "' AND id='" + company + "' FOR UPDATE");
            try (var rows = statement.executeQuery("SELECT version FROM company_sites WHERE tenant_id='" + tenant
                    + "' AND company_id='" + company + "' AND id='" + site + "' FOR UPDATE")) {
                assertTrue(rows.next()); if (rows.getLong(1)!=0) return false;
            }
            if (kind.equals("address")) statement.executeUpdate("INSERT INTO company_site_addresses(site_id,country_code,address_line_1,created_by,updated_by) VALUES('"
                    + site + "','EC','CALLE UNO','ChildWriter','ChildWriter')");
            else statement.executeUpdate("INSERT INTO company_site_contact_points(site_id,contact_type,value,created_by,updated_by) VALUES('"
                    + site + "','EMAIL','mail@host.ec','ChildWriter','ChildWriter')");
            assertEquals(1, statement.executeUpdate("UPDATE company_sites SET version=version+1,updated_at=clock_timestamp(),updated_by='ChildWriter' WHERE id='"
                    + site + "' AND version=0"));
            return true;
        }
    }

    private boolean childWriter(UUID tenant, UUID company, UUID site, String kind) {
        try (var connection = db()) {
            connection.setAutoCommit(false);
            boolean changed = childWriter(connection, tenant, company, site, kind);
            if (changed) connection.commit(); else connection.rollback();
            return changed;
        } catch (Exception failure) { throw new CompletionException(failure); }
    }

    @Test void childAndHu05WritersShareExactlyOneRevisionInBothOrders() throws Exception {
        for (var kind : List.of("address", "contact")) {
            for (boolean writerFirst : List.of(true, false)) {
                var tenant = UUID.randomUUID(); var company = parent(tenant); var site = site(tenant, company, "A");
                try (var holder = db(); var statement = holder.createStatement()) {
                    holder.setAutoCommit(false);
                    if (writerFirst) {
                        assertTrue(childWriter(holder, tenant, company, site, kind));
                        var mutation = CompletableFuture.supplyAsync(() -> mutate(tenant, company, site, "key", "{\"name\":\"NEW\"}", "update"));
                        blocked(1); holder.commit();
                        var result = mutation.get(15, TimeUnit.SECONDS);
                        assertEquals(412, result.statusCode(), result.asString());
                    } else {
                        statement.execute("SELECT id FROM company_sites WHERE id='" + site + "' FOR UPDATE");
                        var mutation = CompletableFuture.supplyAsync(() -> mutate(tenant, company, site, "key", "{\"name\":\"NEW\"}", "update"));
                        blocked(1);
                        var child = CompletableFuture.supplyAsync(() -> childWriter(tenant, company, site, kind));
                        blocked(2); holder.rollback();
                        var result = mutation.get(15, TimeUnit.SECONDS);
                        assertEquals(200, result.statusCode(), result.asString());
                        assertFalse(child.get(15, TimeUnit.SECONDS));
                    }
                }
                assertEquals(1, scalar("SELECT version FROM company_sites WHERE id='" + site + "'"));
                assertEquals(writerFirst ? 1 : 0, scalar("SELECT count(*) FROM " + (kind.equals("address") ? "company_site_addresses" : "company_site_contact_points") + " WHERE site_id='" + site + "'"));
            }
        }
    }
    @Test void aRolledBackCompetingAcceptanceDoesNotConsumeTheKey() throws Exception {
        for (boolean sameCompany : List.of(true, false)) {
            var tenant = UUID.randomUUID(); var firstCompany = parent(tenant);
            var secondCompany = sameCompany ? firstCompany : parent(tenant);
            var firstSite = site(tenant, firstCompany, "A");
            var secondSite = sameCompany ? firstSite : site(tenant, secondCompany, "B");
            try (var holder = db(); var statement = holder.createStatement()) {
                holder.setAutoCommit(false);
                statement.execute("SELECT id FROM companies WHERE id='" + firstCompany + "' FOR UPDATE");
                statement.execute("SELECT id FROM company_sites WHERE id='" + firstSite + "' FOR UPDATE");
                statement.executeUpdate("UPDATE company_sites SET name='PROVISIONAL',version=1 WHERE id='" + firstSite + "'");
                statement.executeUpdate("INSERT INTO company_idempotency_records(tenant_id,operation,idempotency_key,request_hash,company_id,site_id,expected_aggregate_version,response_http_status,result_snapshot,created_by,fingerprint_version,fingerprint_key_id) VALUES('"
                        + tenant + "','company.site.update.v1','shared',repeat('a',64),'" + firstCompany + "','" + firstSite + "',0,200,'{}'::jsonb,'Fixture',1,'FixtureKey')");
                var mutation = CompletableFuture.supplyAsync(() -> mutate(tenant, secondCompany, secondSite, "shared", "{\"name\":\"NEW\"}", "update"));
                blocked(1); holder.rollback();
                var result = mutation.get(15, TimeUnit.SECONDS);
                assertEquals(200, result.statusCode(), result.asString());
                assertEquals("NEW", result.jsonPath().getString("data.name"));
            }
            effects(tenant, 1, 1, 1);
            if (!sameCompany) assertEquals(0, scalar("SELECT version FROM company_sites WHERE id='" + firstSite + "'"));
        }
    }
}
