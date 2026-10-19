package com.alexastudillo.companyregistry;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.contract.OpenApiAssertions;
import com.fasterxml.jackson.databind.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Same real HTTP/database assertions execute against the packaged JVM and native binary. */
@QuarkusIntegrationTest
@QuarkusTestResource(ProcessEnvironmentResource.class)
class PackagedAddressIT {
    @io.quarkus.test.common.http.TestHTTPResource URI runningBase;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private record Fixture(UUID tenant, UUID company, UUID site, String path) {}
    private record Call(String path, String method, String body, long version, String key) {}
    private record Accepted(String method, String path, String body, long expected, String key, int status, JsonNode data) {}
    private record Reply(int status, JsonNode body) {}

    private Connection db() throws Exception {
        return DriverManager.getConnection(ProcessEnvironmentResource.settings.get("quarkus.datasource.jdbc.url"), "company_registry_migration", "migration_test");
    }

    private Fixture fixture() throws Exception {
        var tenant = UUID.randomUUID();
        try (var c = db(); var s = c.createStatement()) {
            UUID company;
            UUID site;
            try (var r = s.executeQuery("INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES('" + tenant
                    + "',uuidv7(),'PACKAGED','COMPANY','Creator','Creator') RETURNING id")) { r.next(); company = r.getObject(1, UUID.class); }
            try (var r = s.executeQuery("INSERT INTO company_sites(tenant_id,company_id,code,name,created_by,updated_by) VALUES('" + tenant + "','"
                    + company + "','SITE','SITE','Creator','Creator') RETURNING id")) { r.next(); site = r.getObject(1, UUID.class); }
            return new Fixture(tenant, company, site, "/v1/companies/" + company + "/sites/" + site + "/addresses");
        }
    }

    private Reply send(HttpClient client, URI base, Fixture f, Call call) throws Exception {
        var path = call.path();
        var method = call.method();
        var body = call.body();
        var version = call.version();
        var key = call.key();
        var process = UUID.randomUUID().toString();
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15)).header("Tenant-Id", f.tenant().toString())
                .header("User-Id", "Process-Actor").header("Process-Id", process);
        if (!method.equals("GET")) request.header("If-Match", Long.toString(version));
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        var response = client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(process, response.headers().firstValue("Process-Id").orElseThrow());
        return new Reply(response.statusCode(), json.readTree(response.body()));
    }

    private Accepted accept(HttpClient client, URI base, Fixture f, Call call, int status) throws Exception {
        var path = call.path();
        var method = call.method();
        var body = call.body();
        var version = call.version();
        var key = call.key();
        var reply = send(client, base, f, new Call(path, method, body, version, key));
        assertEquals(status, reply.status(), reply.body().toString());
        OpenApiAssertions.addressResponseMatches(Files.readString(Path.of("docs/contracts/company-registry.openapi.yaml")), reply.body(),
                method.equals("DELETE") ? "AddressDeletionEnvelope" : "AddressEnvelope");
        return new Accepted(method, path, body, version, key, status, reply.body().path("data"));
    }

    @Test
    void routesPrecisionSnapshotsRestartRotationOfflineReplayAndAllOutboxModes(@TempDir Path work) throws Exception {
        var f = fixture(); var stub = ProcessEnvironmentResource.stub; stub.reset();
        var country = UUID.randomUUID();
        stub.reply("/api/v1/countries/by-alpha2/EC", 200, "{\"status\":200,\"code\":\"successful\",\"data\":{\"id\":\"" + country + "\",\"alpha2Code\":\"EC\",\"status\":\"ACTIVE\"}}");
        stub.reply("/api/v1/countries/" + country + "/administrative-divisions/by-canonical-code/EC-P", 200,
                "{\"status\":200,\"code\":\"successful\",\"data\":{\"id\":\"" + UUID.randomUUID() + "\",\"countryId\":\"" + country
                        + "\",\"canonicalCode\":\"EC-P\",\"status\":\"ACTIVE\"}}");
        var attempts = new ArrayList<Accepted>();
        try (var client = HttpClient.newHttpClient()) {
            assertAddressLifecycle(client, f, attempts);
        }
        try (var c = db(); var s = c.createStatement()) {
            s.execute("UPDATE company_sites SET status='CLOSED' WHERE id='" + f.site() + "'");
            s.execute("UPDATE companies SET status='ARCHIVED' WHERE id='" + f.company() + "'");
        }
        stub.reset();
        for (var mode : List.of("disabled", "stored-only", "published")) {
            int port; try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            var command = launchCommand(port, mode, "10s");
            var log = work.resolve("hu06-" + mode + ".log");
            var process = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            var base = URI.create("http://127.0.0.1:" + port);
            try (var client = HttpClient.newHttpClient()) {
                Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> {
                    if (!process.isAlive()) throw new IllegalStateException("HU06 process exited: " + log);
                    try { return client.send(HttpRequest.newBuilder(base.resolve("/q/health/ready")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
                    catch (java.io.IOException _) { return false; }
                });
                assertAttemptsReplay(client, base, f, attempts);
                localModeFixture(client, base, mode);
                var openapi = client.send(HttpRequest.newBuilder(base.resolve("/q/openapi")).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
                OpenApiAssertions.publishedMatchesSource(Files.readString(Path.of("docs/contracts/company-registry.openapi.yaml")), openapi.body());
                assertEquals(404, send(client, base, f, new Call("/v1/_verification/success", "GET", null, 0, null)).status());
            } finally {
                process.destroy(); if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
            }
        }
        assertTrue(stub.requests().isEmpty());
    }

    private void assertAddressLifecycle(HttpClient client, Fixture f, List<Accepted> attempts) throws Exception {
        var body = "{\"addressType\":\"physical\",\"countryCode\":\"ec\",\"addressLine1\":\" avenida  josé \",\"administrativeDivisionCode\":\"ec-p\",\"latitude\":-0.1234560,\"longitude\":78.123456000}";
        var created = accept(client, runningBase, f, new Call(f.path(), "POST", body, 0, "create"), 201); attempts.add(created);
        assertAll(
                () -> assertEquals(new java.math.BigDecimal("-0.123456"), created.data().path("latitude").decimalValue()),
                () -> assertEquals(new java.math.BigDecimal("78.123456"), created.data().path("longitude").decimalValue()),
                () -> assertEquals(14, created.data().size()),
                () -> assertEquals("EC-P", created.data().path("administrativeDivisionCode").asText()));
        var address = created.data().path("addressId").asText(); var detail = f.path() + "/" + address;
        var patch = accept(client, runningBase, f, new Call(detail, "PATCH", "{\"isPrimary\":true,\"houseNumber\":\"12a\",\"latitude\":90.0000000,\"longitude\":-180.0000000}", 1, "patch"), 200); attempts.add(patch);
        var current = send(client, runningBase, f, new Call(detail, "GET", null, 0, null));
        assertAll(
                () -> assertEquals(0, patch.data().path("latitude").decimalValue().compareTo(java.math.BigDecimal.valueOf(90))),
                () -> assertEquals(0, patch.data().path("longitude").decimalValue().compareTo(java.math.BigDecimal.valueOf(-180))),
                () -> assertEquals(200, current.status()),
                () -> assertEquals(patch.data(), current.body().path("data")));
        OpenApiAssertions.addressResponseMatches(Files.readString(Path.of("docs/contracts/company-registry.openapi.yaml")), current.body(), "AddressEnvelope");
        var noop = accept(client, runningBase, f, new Call(detail, "PATCH", "{}", 2, "noop"), 200); attempts.add(noop);
        var deleted = accept(client, runningBase, f, new Call(detail, "DELETE", null, 2, "delete"), 200); attempts.add(deleted);
        assertAll(
                () -> assertEquals(patch.data(), noop.data()),
                () -> assertEquals(5, deleted.data().size()),
                () -> assertEquals(404, send(client, runningBase, f, new Call(detail, "GET", null, 0, null)).status()),
                () -> assertEquals(200, send(client, runningBase, f, new Call(f.path(), "GET", null, 0, null)).status()));
    }

    private void assertAttemptsReplay(HttpClient client, URI base, Fixture f, List<Accepted> attempts) throws Exception {
        for (var attempt : attempts) {
            var replay = send(client, base, f, new Call(attempt.path(), attempt.method(), attempt.body(), attempt.expected(), attempt.key()));
            assertAll(
                    () -> assertEquals(attempt.status(), replay.status(), replay.body().toString()),
                    () -> assertEquals(attempt.data(), replay.body().path("data")));
        }
    }

    private void localModeFixture(HttpClient client, URI base, String mode) throws Exception {
        var f = fixture();
        try (var c = db(); var s = c.createStatement()) {
            for (int i = 0; i < 3; i++) s.execute("INSERT INTO company_site_addresses(site_id,address_type,country_code,address_line_1,created_by,updated_by) VALUES('"
                    + f.site() + "','PHYSICAL','EC','LINE','Creator','Creator')");
        }
        var page = send(client, base, f, new Call(f.path() + "?limit=1", "GET", null, 0, null));
        var cursor = page.body().path("nextCursor").asText();
        var next = send(client, base, f, new Call(f.path() + "?limit=200&cursor=" + cursor, "GET", null, 0, null));
        assertAll(
                () -> assertEquals(200, page.status()),
                () -> assertEquals(1, page.body().path("numberOfElements").asInt()),
                () -> assertFalse(cursor.isBlank()),
                () -> assertEquals(200, next.status()),
                () -> assertEquals(2, next.body().path("numberOfElements").asInt()));
        OpenApiAssertions.addressResponseMatches(Files.readString(Path.of("docs/contracts/company-registry.openapi.yaml")), page.body(), "AddressCollectionEnvelope");
        var detail = f.path() + "/" + page.body().path("data").get(0).path("addressId").asText();
        accept(client, base, f, new Call(detail, "PATCH", "{\"houseNumber\":\"12A\"}", 0, "local"), 200);
        accept(client, base, f, new Call(detail, "DELETE", "{}", 1, "remove"), 200);
        try (var c = db(); var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM company_outbox_events WHERE aggregate_id='" + f.site() + "' ORDER BY aggregate_version")) {
            int count = 0;
            while (r.next()) {
                count++;
                var payload = json.readTree(r.getString("payload"));
                var event = json.createObjectNode();
                event.put("eventId", r.getString("id")); event.put("tenantId", r.getString("tenant_id"));
                event.put("aggregateType", r.getString("aggregate_type")); event.put("aggregateId", r.getString("aggregate_id"));
                event.put("aggregateVersion", r.getLong("aggregate_version")); event.put("eventType", r.getString("event_type"));
                event.put("eventSchemaVersion", r.getInt("event_schema_version")); event.put("occurredAt", r.getTimestamp("occurred_at").toInstant().toString());
                event.put("correlationId", r.getString("correlation_id")); event.putNull("causationId"); event.set("payload", payload);
                var schema = com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                        .getSchema(Files.readString(Path.of("docs/contracts/events/company.site.updated.v1.schema.json")), com.networknt.schema.InputFormat.JSON);
                int expectedVersion = count;
                assertAll(
                        () -> assertEquals(expectedVersion, r.getLong("aggregate_version")),
                        () -> assertEquals("company.site.updated.v1", r.getString("event_type")),
                        () -> assertEquals(Set.of("siteId", "companyId", "code", "name", "siteType", "status", "updatedAt", "updatedBy"), fieldNames(payload)),
                        () -> assertEquals("PENDING", r.getString("status")),
                        () -> assertEquals(0, r.getInt("publish_attempts")),
                        () -> assertTrue(schema.validate(event).isEmpty(), () -> schema.validate(event).toString()));
            }
            assertEquals(mode.equals("disabled") ? 0 : 2, count);
        }
    }

    private static Set<String> fieldNames(JsonNode node) { var names = new HashSet<String>(); node.fieldNames().forEachRemaining(names::add); return names; }

    @ParameterizedTest(name = "invalid budget {0} fails during startup")
    @ValueSource(strings = {"0s", "PT-1S", "PT2562048H"})
    void invalidAddressBudgetFailsDuringStartup(String budget, @TempDir Path work) throws Exception {
        var log = work.resolve("hu06-invalid-budget.log");
        var process = new ProcessBuilder(launchCommand(0, "disabled", budget)).directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(40, TimeUnit.SECONDS), () -> "Budget " + budget + " did not abort startup");
            assertNotEquals(0, process.exitValue(), budget);
            assertTrue(Files.readString(log).contains("site-address-budget-invalid"), budget);
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); } }
    }

    private static List<String> launchCommand(int port, String mode, String budget) {
        var build = Path.of("build").toAbsolutePath();
        var command = new ArrayList<String>();
        if (Boolean.getBoolean("native.artifact")) command.add(build.resolve("company-registry-service-1.0.0-SNAPSHOT-runner").toString());
        else command.add(Path.of(System.getProperty("java.home"), "bin/java").toString());
        ProcessEnvironmentResource.settings.forEach((key, value) -> command.add("-D" + key + "=" + value));
        command.addAll(List.of("-Dquarkus.profile=test", "-Dquarkus.http.port=" + port, "-Dquarkus.http.host=127.0.0.1",
                "-Dcompany-registry.idempotency.active-key-id=process-k2", "-Dcompany-registry.outbox.mode=" + mode,
                "-Dcompany-registry.site-address.wait-budget=" + budget,
                "-Dquarkus.rest-client.party-registry.url=http://127.0.0.1:9", "-Dquarkus.rest-client.geographic-reference.url=http://127.0.0.1:9"));
        if (!Boolean.getBoolean("native.artifact")) command.addAll(List.of("-jar", build.resolve("quarkus-app/quarkus-run.jar").toString()));
        return command;
    }
}
