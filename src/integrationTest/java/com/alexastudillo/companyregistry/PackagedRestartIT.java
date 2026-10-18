package com.alexastudillo.companyregistry;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reboots the same packaged/native variant without source-tree access and
 * checks persisted migration history.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(ProcessEnvironmentResource.class)
class PackagedRestartIT {
    @Test
    void repeatedProcessStartupPreservesFlywayHistoryAndRuntimeCannotDdl(@TempDir Path workingDirectory) throws Exception {
        var settings = ProcessEnvironmentResource.settings;
        var jdbc = settings.get("quarkus.datasource.jdbc.url");
        DatabaseBaseline baseline;
        try (var connection = DriverManager.getConnection(jdbc, "company_registry_runtime", "runtime_test")) {
            baseline = assertInitialDatabaseState(connection);
        }
        var directory = Path.of(Boolean.getBoolean("verification.artifact") ? "build/verification" : "build")
                .toAbsolutePath();
        var command = new ArrayList<String>();
        if (Boolean.getBoolean("native.artifact")) {
            var binary = directory.resolve("company-registry-service-1.0.0-SNAPSHOT-runner");
            assertNativeBinary(binary);
            command.add(binary.toString());
        } else
            command.add(Path.of(System.getProperty("java.home"), "bin/java").toString());
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        settings.forEach((key, value) -> command.add("-D" + key + "=" + value));
        command.add("-Dquarkus.profile=test");
        command.add("-Dquarkus.http.port=" + port);
        command.add("-Dquarkus.http.host=127.0.0.1");
        command.add("-Dquarkus.rest-client.party-registry.url=http://127.0.0.1:9");
        command.add("-Dquarkus.rest-client.geographic-reference.url=http://127.0.0.1:9");
        if (!Boolean.getBoolean("native.artifact")) {
            command.add("-jar");
            command.add(directory.resolve("quarkus-app/quarkus-run.jar").toString());
        }
        var log = Files.createTempFile(workingDirectory, "company-secondary-", ".log");
        var process = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var base = "http://127.0.0.1:" + port;
            await().alias("Second process did not become ready; log: " + log)
                    .atMost(Duration.ofSeconds(45))
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> {
                        if (!process.isAlive()) {
                            throw new IllegalStateException("Second process exited prematurely; log: " + log);
                        }
                        try {
                            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/q/health/ready"))
                                    .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                            return response.statusCode() == 200;
                        } catch (java.io.IOException _) {
                            return false;
                        }
                    });
            var openapi = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/q/openapi")).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, openapi.statusCode());
            assertTrue(openapi.body().contains("Company Registry Service"));
            if (Boolean.getBoolean("verification.artifact")) {
                assertVerificationEndpoints(client, base);
            }
            try (var connection = DriverManager.getConnection(jdbc, "company_registry_runtime", "runtime_test")) {
                assertEquals(baseline.history(), history(connection));
                assertEquals("1", scalar(connection, "SELECT count(*) FROM companies WHERE id='" + baseline.marker() + "'"));
            }
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private record DatabaseBaseline(List<String> history, UUID marker) {}

    private static DatabaseBaseline assertInitialDatabaseState(Connection connection) throws Exception {
        assertEquals("7", scalar(connection,
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'"));
        assertEquals("7", scalar(connection,
                "SELECT count(*) FROM pg_type t JOIN pg_namespace n ON t.typnamespace=n.oid WHERE n.nspname='public' AND t.typtype='e'"));
        var before = history(connection);
        assertEquals(6, before.size());
        assertEquals("company_registry_migration", scalar(connection,
                "SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='companies'"));
        var marker = UUID.fromString(scalar(connection,
                "INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) "
                        + "VALUES(uuidv7(),uuidv7(),'RESTART','RESTART','test','test') RETURNING id"));
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            var failure = assertThrows(SQLException.class,
                    () -> statement.execute("CREATE TABLE forbidden_native_ddl(id integer)"));
            assertEquals("42501", failure.getSQLState());
        }
        connection.rollback();
        return new DatabaseBaseline(before, marker);
    }

    private static void assertNativeBinary(Path binary) throws Exception {
        assertTrue(Files.isExecutable(binary));
        try (var file = Files.newInputStream(binary)) {
            assertArrayEquals(new byte[] { 0x7f, 'E', 'L', 'F' }, file.readNBytes(4));
        }
    }

    private static void assertVerificationEndpoints(HttpClient client, String base) throws Exception {
        var first = UUID.fromString(
                given().header("Tenant-Id", UUID.randomUUID().toString()).header("User-Id", "Actor-aB")
                        .header("Process-Id", UUID.randomUUID().toString()).get("/v1/_verification/uuid").then()
                        .statusCode(200).extract().path("data.value"));
        var request = HttpRequest.newBuilder(URI.create(base + "/v1/_verification/uuid"))
                .timeout(Duration.ofSeconds(5))
                .header("Tenant-Id", UUID.randomUUID().toString()).header("User-Id", "Actor-aB")
                .header("Process-Id", UUID.randomUUID().toString()).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var second = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body())
                .path("data").path("value").asText());
        assertEquals(7, first.version());
        assertEquals(7, second.version());
        assertNotEquals(first, second);
        // Providers in this independent process point at port 9: verify
        // refused connections as well as provider-returned 503 responses.
        for (var reference : List.of("party/" + UUID.randomUUID(), "country/EC", "division/EC/EC-P")) {
            var unavailable = HttpRequest.newBuilder(URI.create(base + "/v1/_verification/references/" + reference))
                    .timeout(Duration.ofSeconds(5)).header("Tenant-Id", UUID.randomUUID().toString())
                    .header("User-Id", "Actor-aB").header("Process-Id", UUID.randomUUID().toString()).build();
            var result = client.send(unavailable, HttpResponse.BodyHandlers.ofString());
            assertEquals(503, result.statusCode());
            assertEquals("dependency-unavailable", new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.body()).path("code").asText());
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static List<String> history(Connection connection) throws Exception {
        var rows = new ArrayList<String>();
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT version||':'||checksum::text||':'||installed_on::text||':'||success::text FROM flyway_schema_history ORDER BY installed_rank")) {
            while (result.next())
                rows.add(result.getString(1));
        }
        return rows;
    }
}
