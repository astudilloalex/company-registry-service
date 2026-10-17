package com.alexastudillo.companyregistry;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.contract.CompanyProviderFixtures;
import com.fasterxml.jackson.databind.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The same assertions run against the actual productive JVM and native
 * executables.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(ProcessEnvironmentResource.class)
class PackagedCompanyIT {
    private final ObjectMapper json = new ObjectMapper();

    private Map<String, String> headers(UUID tenant) {
        return Map.of("Tenant-Id", tenant.toString(), "User-Id", "Actor-aB", "Process-Id", UUID.randomUUID().toString(),
                "Idempotency-Key", "Packaged-key");
    }

    private String body(UUID party) {
        return "{\"partyId\":\"" + party
                + "\",\"code\":\" acme \",\"operatingName\":\" compañía ágil \",\"logoUrl\":\"https://host/Logo-aB.png\"}";
    }

    private void provider(UUID party) {
        var stub = ProcessEnvironmentResource.stub;
        stub.reply("/v1/parties/" + party, 200, CompanyProviderFixtures.detail(party, "LEGAL_ENTITY", "ACTIVE"));
        stub.reply("/v1/parties/" + party + "/identifiers", 200,
                CompanyProviderFixtures.fullFirstPage(party, UUID.randomUUID(), "next"));
        stub.reply("/v1/parties/" + party + "/identifiers?limit=200&cursor=next", 200, CompanyProviderFixtures
                .page(CompanyProviderFixtures.identifier(party, UUID.randomUUID(), "REVOKED"), 1, null, 201, 2));
    }

    private Connection database() throws Exception {
        return DriverManager.getConnection(ProcessEnvironmentResource.settings.get("quarkus.datasource.jdbc.url"),
                "company_registry_migration", "migration_test");
    }

    private long count(Connection c, String table, UUID tenant) throws Exception {
        try (var s = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id=?")) {
            s.setObject(1, tenant);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    @Test
    void realCreationCurrentReadsAndOriginalReplaySurviveRestartRotationAndProviderLoss(@TempDir Path work)
            throws Exception {
        var stub = ProcessEnvironmentResource.stub;
        stub.reset();
        var tenant = UUID.randomUUID();
        var party = UUID.randomUUID();
        provider(party);
        var h = headers(tenant);
        var accepted = given().headers(h).contentType("application/json").body(body(party)).post("/v1/companies").then()
                .statusCode(201)
                .header("Process-Id", equalTo(h.get("Process-Id"))).body("data.party.identifiers.size()", equalTo(201))
                .body("data.party.identifiers[0].isPrimary", equalTo(true))
                .body("data.party.legalEntityDetails.incorporatedOn", equalTo("2020-01-15")).extract().asString();
        var original = json.readTree(accepted).path("data");
        var id = original.path("companyId").asText();
        assertEquals(7, UUID.fromString(id).version());
        var get = given().headers(h).get("/v1/companies/" + id).then().statusCode(200).extract().asString();
        assertEquals(original, json.readTree(get).path("data"));
        given().headers(h).queryParam("limit", 1).get("/v1/companies").then().statusCode(200)
                .body("numberOfElements", equalTo(1)).body("data[0].companyId", equalTo(id));
        try (var c = database();
                var s = c.prepareStatement(
                        "UPDATE companies SET operating_name='LATER',updated_by='Later-actor',version=1 WHERE tenant_id=?")) {
            s.setObject(1, tenant);
            assertEquals(1, s.executeUpdate());
        }
        stub.reply("/v1/parties/" + party, 200,
                CompanyProviderFixtures.detail(party, "LEGAL_ENTITY", "ARCHIVED").replace("COMPAÑÍA", "LATER PARTY"));
        stub.reply("/v1/parties/" + party + "/identifiers", 200, CompanyProviderFixtures.page("", 0, null));
        given().headers(h).get("/v1/companies/" + id).then().statusCode(200)
                .body("data.operatingName", equalTo("LATER")).body("data.party.recordStatus", equalTo("ARCHIVED"))
                .body("data.party.identifiers", empty());
        stub.reset();
        var retry = new HashMap<>(h);
        retry.put("Process-Id", UUID.randomUUID().toString());
        retry.put("User-Id", "Retry-actor");
        var replay = given().headers(retry).contentType("application/json").body(body(party)).post("/v1/companies")
                .then().statusCode(201).header("Process-Id", equalTo(retry.get("Process-Id"))).extract().asString();
        assertEquals(original, json.readTree(replay).path("data"));
        assertTrue(stub.requests().isEmpty());
        assertRestartReplay(work, retry, original, id, party);
        assertOutboxEvent(tenant, original);
    }

    private void assertRestartReplay(Path work, Map<String, String> retry, JsonNode original, String id, UUID party)
            throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var command = new ArrayList<String>();
        var directory = Path.of(Boolean.getBoolean("verification.artifact") ? "build/verification" : "build")
                .toAbsolutePath();
        if (Boolean.getBoolean("native.artifact")) {
            var binary = directory.resolve("company-registry-service-1.0.0-SNAPSHOT-runner");
            try (var stream = Files.newInputStream(binary)) {
                assertArrayEquals(new byte[] { 0x7f, 'E', 'L', 'F' }, stream.readNBytes(4));
            }
            command.add(binary.toString());
        } else
            command.add(Path.of(System.getProperty("java.home"), "bin/java").toString());
        ProcessEnvironmentResource.settings.forEach((key, value) -> command.add("-D" + key + "=" + value));
        command.addAll(List.of("-Dquarkus.profile=test", "-Dquarkus.http.port=" + port, "-Dquarkus.http.host=127.0.0.1",
                "-Dcompany-registry.idempotency.active-key-id=process-k2", "-Dcompany-registry.outbox.mode=disabled",
                "-Dquarkus.rest-client.party-registry.url=http://127.0.0.1:9",
                "-Dquarkus.rest-client.geographic-reference.url=http://127.0.0.1:9"));
        if (!Boolean.getBoolean("native.artifact"))
            command.addAll(List.of("-jar", directory.resolve("quarkus-app/quarkus-run.jar").toString()));
        var log = work.resolve("hu01-restarted.log");
        var process = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var base = "http://127.0.0.1:" + port;
            org.awaitility.Awaitility.await().alias("HU01 restart; log: " + log).atMost(Duration.ofSeconds(45))
                    .until(() -> {
                        if (!process.isAlive())
                            throw new IllegalStateException("Restart failed: " + log);
                        try {
                            return client.send(
                                    HttpRequest.newBuilder(URI.create(base + "/q/health/ready"))
                                            .timeout(Duration.ofSeconds(2)).build(),
                                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
                        } catch (java.io.IOException _) {
                            return false;
                        }
                    });
            var request = HttpRequest.newBuilder(URI.create(base + "/v1/companies")).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json");
            retry.forEach(request::header);
            var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(body(party))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode());
            assertEquals(original, json.readTree(response.body()).path("data"));
            assertEquals(retry.get("Process-Id"), response.headers().firstValue("Process-Id").orElseThrow());
            var current = HttpRequest.newBuilder(URI.create(base + "/v1/companies/" + id))
                    .timeout(Duration.ofSeconds(5));
            retry.forEach(current::header);
            assertEquals(503, client.send(current.GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var different = HttpRequest.newBuilder(URI.create(base + "/v1/companies")).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json");
            retry.forEach(different::header);
            assertEquals(409,
                    client.send(different
                            .POST(HttpRequest.BodyPublishers.ofString(body(party).replace("acme", "different")))
                            .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private void assertOutboxEvent(UUID tenant, JsonNode original) throws Exception {
        try (var c = database()) {
            assertEquals(1, count(c, "companies", tenant));
            assertEquals(1, count(c, "company_idempotency_records", tenant));
            assertEquals(1, count(c, "company_outbox_events", tenant));
            try (var statement = c.prepareStatement("SELECT * FROM company_outbox_events WHERE tenant_id=?")) {
                statement.setObject(1, tenant);
                try (var row = statement.executeQuery()) {
                    assertTrue(row.next());
                    var event = json.createObjectNode();
                    event.put("eventId", row.getString("id"));
                    event.put("tenantId", row.getString("tenant_id"));
                    event.put("aggregateType", row.getString("aggregate_type"));
                    event.put("aggregateId", row.getString("aggregate_id"));
                    event.put("aggregateVersion", row.getLong("aggregate_version"));
                    event.put("eventType", row.getString("event_type"));
                    event.put("eventSchemaVersion", row.getInt("event_schema_version"));
                    event.put("occurredAt", row.getTimestamp("occurred_at").toInstant().toString());
                    event.put("correlationId", row.getString("correlation_id"));
                    event.putNull("causationId");
                    event.set("payload", json.readTree(row.getString("payload")));
                    var schema = com.networknt.schema.SchemaRegistry
                            .withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12).getSchema(
                                    Files.readString(Path.of("docs/contracts/events/company.created.v1.schema.json")),
                                    com.networknt.schema.InputFormat.JSON);
                    assertTrue(schema.validate(event).isEmpty(), () -> schema.validate(event).toString());
                    assertEquals(original.path("companyId"), event.path("payload").path("companyId"));
                    assertEquals(original.path("createdAt"), event.path("payload").path("createdAt"));
                    assertEquals("DRAFT", event.path("payload").path("status").asText());
                    assertEquals("PENDING", row.getString("status"));
                    assertEquals(0, row.getInt("publish_attempts"));
                    assertEquals(0, row.getLong("version"));
                    assertFalse(row.next());
                }
            }
        }
    }

    @Test
    void realProcessRollbackAndServerTimeoutAllowTheSameKeyToRetry() throws Exception {
        var stub = ProcessEnvironmentResource.stub;
        stub.reset();
        var tenant = UUID.randomUUID();
        var party = UUID.randomUUID();
        provider(party);
        var h = headers(tenant);
        var function = "packaged_fail_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = database(); var s = c.createStatement()) {
            s.execute("CREATE FUNCTION " + function
                    + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='" + tenant
                    + "'::uuid THEN RAISE EXCEPTION 'packaged-rollback' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
            s.execute("CREATE TRIGGER " + function
                    + " BEFORE INSERT ON company_idempotency_records FOR EACH ROW EXECUTE FUNCTION " + function + "()");
            try {
                given().headers(h).contentType("application/json").body(body(party)).post("/v1/companies").then()
                        .statusCode(500);
                assertEquals(0, count(c, "companies", tenant));
                assertEquals(0, count(c, "company_idempotency_records", tenant));
                assertEquals(0, count(c, "company_outbox_events", tenant));
            } finally {
                s.execute("DROP TRIGGER " + function + " ON company_idempotency_records");
                s.execute("DROP FUNCTION " + function + "()");
            }
        }
        try (var holder = database();
                var statement = holder.createStatement();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            holder.setAutoCommit(false);
            statement.execute("LOCK TABLE companies IN SHARE MODE");
            var call = executor.submit(
                    () -> given().headers(h).contentType("application/json").body(body(party)).post("/v1/companies"));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(4)).until(() -> {
                try (var c = database();
                        var s = c.createStatement();
                        var r = s.executeQuery(
                                "SELECT count(*) FROM pg_locks WHERE relation='companies'::regclass AND mode='RowExclusiveLock' AND NOT granted")) {
                    r.next();
                    return r.getLong(1) > 0;
                }
            });
            assertEquals(503, call.get(8, TimeUnit.SECONDS).statusCode());
            holder.rollback();
        }
        given().headers(h).contentType("application/json").body(body(party)).post("/v1/companies").then()
                .statusCode(201);
        try (var c = database()) {
            assertEquals(1, count(c, "companies", tenant));
            assertEquals(1, count(c, "company_idempotency_records", tenant));
            assertEquals(1, count(c, "company_outbox_events", tenant));
        }
    }
}
