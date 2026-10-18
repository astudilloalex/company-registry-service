package com.alexastudillo.companyregistry;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual productive JVM/native process, external provider/SQL inspection and a preserved database across restarts. */
@QuarkusIntegrationTest
@QuarkusTestResource(ProcessEnvironmentResource.class)
class PackagedCompanyLifecycleIT {
    @io.quarkus.test.common.http.TestHTTPResource URI runningBase;
    private final ObjectMapper json = new ObjectMapper();
    record Acceptance(String action, long version, String key, JsonNode data) {}

    private Connection db() throws SQLException {
        return DriverManager.getConnection(ProcessEnvironmentResource.settings.get("quarkus.datasource.jdbc.url"),
                "company_registry_migration", "migration_test");
    }

    private Map<String, String> headers(UUID tenant, long version, String key) {
        var headers = new HashMap<String, String>();
        headers.put("Tenant-Id", tenant.toString()); headers.put("User-Id", "Actor-aB");
        headers.put("Process-Id", UUID.randomUUID().toString()); headers.put("If-Match", Long.toString(version));
        if (key != null) headers.put("Idempotency-Key", key);
        return headers;
    }

    private HttpResponse<String> call(HttpClient client, String base, String path, String method, String body,
            Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        headers.forEach(request::header);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode(), response::body);
        return json.readTree(response.body()).path("data");
    }

    private void provider(UUID party, String kind, String state) {
        ProcessEnvironmentResource.stub.reply("/v1/parties/" + party, 200, "{\"status\":200,\"code\":\"successful\",\"data\":{\"partyId\":\""
                + party + "\",\"type\":\"" + kind + "\",\"recordStatus\":\"" + state + "\"}}");
    }

    @Test void transitionsAndHistoricalNullSnapshotsSurviveRestartKeyRotationAndModeChanges(@TempDir Path work) throws Exception {
        var tenant = UUID.randomUUID();
        var party = UUID.randomUUID();
        UUID company;
        try (var c = db(); var statement = c.createStatement(); var rows = statement.executeQuery(
                "INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES('" + tenant
                        + "','" + party + "','HU03','COMPANY','Creator-aB','Creator-aB') RETURNING id")) {
            assertTrue(rows.next()); company = rows.getObject(1, UUID.class);
        }
        String path = "/v1/companies/" + company;
        String base = runningBase.toString().replaceAll("/$", "");
        var accepted = new ArrayList<Acceptance>();
        ProcessEnvironmentResource.stub.reset();
        try (var client = HttpClient.newHttpClient()) {
            var h = headers(tenant, 0, "site");
            var site = data(call(client, base, path + "/sites", "POST",
                    "{\"code\":\"HQ\",\"name\":\"HQ\",\"siteType\":\"HEADQUARTERS\"}", h), 201).path("siteId").asText();
            data(call(client, base, path + "/sites/" + site + "/activate", "POST", null, headers(tenant, 0, "site-activate")), 200);
            provider(party, "NATURAL_PERSON", "ACTIVE");
            var invalid = call(client, base, path + "/activate", "POST", null, headers(tenant, 0, "activate"));
            assertEquals(409, invalid.statusCode());
            assertEquals("party-must-be-legal-entity", json.readTree(invalid.body()).path("code").asText());
            provider(party, "LEGAL_ENTITY", "ACTIVE");
            var actions = List.of("activate", "deactivate", "activate", "deactivate", "archive");
            for (int i = 0; i < actions.size(); i++) {
                var action = actions.get(i);
                String key = "hu03-" + i;
                var result = data(call(client, base, path + "/" + action, "POST", i % 2 == 0 ? null : "{}", headers(tenant, i, key)), 200);
                assertEquals(i + 1, result.path("version").asInt());
                assertEquals(10, result.size());
                assertFalse(result.has("description")); assertFalse(result.has("logoUrl")); assertFalse(result.has("party"));
                accepted.add(new Acceptance(action, i, key, result));
                if (i == 2) ProcessEnvironmentResource.stub.reply("/v1/parties/" + party, 503, "{}");
            }
            for (var observed : ProcessEnvironmentResource.stub.requests()) {
                assertEquals("/v1/parties/" + party, observed.path());
                assertEquals(tenant.toString(), observed.header("Tenant-Id"));
                assertEquals("Actor-aB", observed.header("User-Id"));
            }
            var current = data(call(client, base, path + "/sites/" + site, "GET", null, headers(tenant, 0, null)), 200);
            assertEquals(1, current.path("version").asInt());
            assertEquals("ACTIVE", current.path("status").asText());
            assertFalse(current.path("effectivelyOperational").asBoolean());
            assertEvents(company, accepted);
            for (String mode : List.of("disabled", "stored-only", "published")) restartReplay(work, mode, tenant, path, accepted);
        }
    }

    private void assertEvents(UUID company, List<Acceptance> accepted) throws Exception {
        try (var c = db(); var statement = c.createStatement(); var rows = statement.executeQuery(
                "SELECT * FROM company_outbox_events WHERE aggregate_id='" + company + "' ORDER BY aggregate_version")) {
            for (var acceptance : accepted) {
                assertTrue(rows.next());
                String type = acceptance.action().equals("activate") ? "company.activated.v1"
                        : acceptance.action().equals("deactivate") ? "company.deactivated.v1" : "company.updated.v1";
                assertEquals(type, rows.getString("event_type"));
                assertEquals(acceptance.version() + 1, rows.getLong("aggregate_version"));
                var payload = json.readTree(rows.getString("payload"));
                assertEquals(7, payload.size());
                assertEquals(acceptance.data().path("status"), payload.path("status"));
                assertEquals(rows.getTimestamp("occurred_at").toInstant().toString(), payload.path("updatedAt").asText());
                assertEquals("PENDING", rows.getString("status"));
                assertEquals(0, rows.getInt("publish_attempts")); assertEquals(0, rows.getLong("version"));
                var envelope = json.createObjectNode();
                envelope.put("eventId", rows.getString("id")); envelope.put("tenantId", rows.getString("tenant_id"));
                envelope.put("aggregateType", "COMPANY"); envelope.put("aggregateId", company.toString());
                envelope.put("aggregateVersion", rows.getLong("aggregate_version")); envelope.put("eventType", type);
                envelope.put("eventSchemaVersion", 1); envelope.put("occurredAt", rows.getTimestamp("occurred_at").toInstant().toString());
                envelope.put("correlationId", rows.getString("correlation_id")); envelope.putNull("causationId"); envelope.set("payload", payload);
                try (var resource = getClass().getResourceAsStream("/META-INF/contracts/events/" + type + ".schema.json")) {
                    assertNotNull(resource);
                    var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                            .getSchema(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), com.networknt.schema.InputFormat.JSON);
                    assertTrue(schema.validate(envelope).isEmpty());
                }
            }
            assertFalse(rows.next());
        }
    }

    private void restartReplay(Path work, String mode, UUID tenant, String path, List<Acceptance> accepted) throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var directory = Path.of(Boolean.getBoolean("verification.artifact") ? "build/verification" : "build").toAbsolutePath();
        var command = new ArrayList<String>();
        if (Boolean.getBoolean("native.artifact")) {
            var binary = directory.resolve("company-registry-service-1.0.0-SNAPSHOT-runner");
            try (var bytes = Files.newInputStream(binary)) { assertArrayEquals(new byte[] {0x7f, 'E', 'L', 'F'}, bytes.readNBytes(4)); }
            command.add(binary.toString());
        } else command.add(Path.of(System.getProperty("java.home"), "bin/java").toString());
        ProcessEnvironmentResource.settings.forEach((key, value) -> command.add("-D" + key + "=" + value));
        command.addAll(List.of("-Dquarkus.profile=test", "-Dquarkus.http.port=" + port, "-Dquarkus.http.host=127.0.0.1",
                "-Dcompany-registry.idempotency.active-key-id=process-k2", "-Dcompany-registry.outbox.mode=" + mode,
                "-Dquarkus.rest-client.party-registry.url=http://127.0.0.1:9", "-Dquarkus.rest-client.geographic-reference.url=http://127.0.0.1:9"));
        if (!Boolean.getBoolean("native.artifact")) command.addAll(List.of("-jar", directory.resolve("quarkus-app/quarkus-run.jar").toString()));
        Path log = work.resolve("hu03-" + mode + ".log");
        var process = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try (var client = HttpClient.newHttpClient()) {
            String base = "http://127.0.0.1:" + port;
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> {
                assertTrue(process.isAlive(), () -> "Restart failed: " + log);
                try { return call(client, base, "/q/health/ready", "GET", null, Map.of()).statusCode() == 200; }
                catch (java.io.IOException _) { return false; }
            });
            for (var acceptance : accepted) {
                var headers = headers(tenant, acceptance.version(), acceptance.key());
                headers.put("User-Id", "ReplayActor");
                var response = call(client, base, path + "/" + acceptance.action(), "POST", "{}", headers);
                assertEquals(acceptance.data(), data(response, 200));
                assertEquals(headers.get("Process-Id"), response.headers().firstValue("Process-Id").orElseThrow());
            }
            assertEquals(200, call(client, base, "/q/openapi", "GET", null, Map.of()).statusCode());
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
        }
    }
}
