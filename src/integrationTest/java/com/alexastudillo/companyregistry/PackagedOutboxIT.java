package com.alexastudillo.companyregistry;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.outbox.StoredEvent;
import com.alexastudillo.companyregistry.contract.*;
import com.alexastudillo.companyregistry.infrastructure.outbox.OutboxDestination;
import com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaRegistry;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs unchanged against the production JVM artifact and real ELF executable, outside the source tree. */
@QuarkusIntegrationTest
@QuarkusTestResource(value = OutboxProcessEnvironmentResource.class, restrictToAnnotatedClass = true)
class PackagedOutboxIT {
    private final ObjectMapper json = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(30);

    @org.junit.jupiter.api.BeforeEach void clearDedicatedDeliveryDatabase() throws SQLException {
        sql("delete from company_outbox_events");
    }

    private Connection db() throws SQLException {
        return DriverManager.getConnection(DeliveryEnvironmentResource.settings.get("quarkus.datasource.jdbc.url"),
                "company_registry_migration", "migration_test");
    }
    private void sql(String sql) throws SQLException {
        try (var connection = db(); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private String state(UUID id) throws SQLException {
        try (var connection = db(); var statement = connection.prepareStatement(
                "select status||':'||publish_attempts||':'||coalesce(last_error_code,'none') from company_outbox_events where id=?")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : "missing"; }
        }
    }
    private void awaitState(UUID id, String expected) {
        org.awaitility.Awaitility.await().atMost(WAIT).until(() -> state(id).startsWith(expected));
    }
    private StoredEvent seed(String type) throws Exception {
        var e = OutboxFixtures.example(type);
        var fact = new StoredEvent(new UuidV7Generator().next(), UUID.randomUUID(), e.aggregate(), e.kind(), e.context(), e.payload());
        insert(fact);
        return fact;
    }
    private void insert(StoredEvent fact) throws Exception {
        try (var connection = db(); var statement = connection.prepareStatement("""
                insert into company_outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,
                    event_schema_version,payload,occurred_at,correlation_id,created_by,updated_by)
                values (?,?,cast(? as company_outbox_aggregate_type),?,?,?,?,cast(? as jsonb),?,?,'process-test','process-test')
                """)) {
            statement.setObject(1, fact.eventId()); statement.setObject(2, fact.tenantId());
            statement.setString(3, fact.aggregate().type().name()); statement.setObject(4, fact.aggregate().id());
            statement.setLong(5, fact.aggregate().version()); statement.setString(6, fact.kind().type());
            statement.setShort(7, fact.kind().schemaVersion()); statement.setString(8, json.writeValueAsString(fact.payload()));
            statement.setTimestamp(9, Timestamp.from(fact.kind().occurredAt())); statement.setString(10, fact.context().correlationId());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private final class Queue implements AutoCloseable {
        final com.rabbitmq.client.Connection connection = DeliveryEnvironmentResource.connection();
        final com.rabbitmq.client.Channel channel = connection.createChannel();
        final String name;
        Queue(String route) throws Exception {
            channel.exchangeDeclare("company.events", "topic", true);
            name = channel.queueDeclare().getQueue(); channel.queueBind(name, "company.events", route);
        }
        com.rabbitmq.client.GetResponse message() {
            var result = new java.util.concurrent.atomic.AtomicReference<com.rabbitmq.client.GetResponse>();
            org.awaitility.Awaitility.await().atMost(WAIT).until(() -> {
                result.set(channel.basicGet(name, true)); return result.get() != null;
            });
            return result.get();
        }
        @Override public void close() throws Exception { try { channel.close(); } finally { connection.close(); } }
    }

    @Test void sevenTypesAndRoutingWorkAcrossTwoPublisherProcesses(@TempDir Path work) throws Exception {
        try (var queue = new Queue("integration.#")) {
            try (var first = new DeliveryProcess(work.resolve("first"), Map.of("company-registry.outbox.publisher.batch-size", "1"));
                 var second = new DeliveryProcess(work.resolve("second"), Map.of("company-registry.outbox.publisher.batch-size", "1"))) {
                var facts = new ArrayList<StoredEvent>();
                for (var type : OutboxDestination.EVENT_TYPES) facts.add(seed(type));
                for (var fact : facts) awaitState(fact.eventId(), "PUBLISHED:1:");
                var types = new HashSet<String>();
                for (int i = 0; i < 7; i++) {
                    var message = queue.message(); var wire = json.readTree(message.getBody());
                    String type = wire.path("eventType").asText(); types.add(type);
                    assertEquals("integration." + type, message.getEnvelope().getRoutingKey());
                    assertEquals(wire.path("eventId").asText(), message.getProps().getMessageId());
                    try (var input = getClass().getResourceAsStream("/META-INF/contracts/events/" + type + ".schema.json")) {
                        assertNotNull(input);
                        var schema = SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                                .getSchema(new String(input.readAllBytes(), StandardCharsets.UTF_8), com.networknt.schema.InputFormat.JSON);
                        assertTrue(schema.validate(wire).isEmpty(), () -> schema.validate(wire).toString());
                    }
                }
                assertEquals(OutboxDestination.EVENT_TYPES, types);
                assertNull(queue.channel.basicGet(queue.name, true));
                assertTrue(first.process.isAlive() && second.process.isAlive());
                assertTrue(java.nio.file.Files.readString(first.log).contains("outcome=CONFIRMED"));
                assertTrue(java.nio.file.Files.readString(second.log).contains("outcome=CONFIRMED"));
            }
        }
    }

    @Test void httpCreationReplayAndNoOpPreserveOneOriginalFact(@TempDir Path work) throws Exception {
        var tenant = UUID.randomUUID(); var party = UUID.randomUUID();
        provider(party);
        try (var queue = new Queue("company.created.v1"); var process = new DeliveryProcess(work, Map.of(
                "company-registry.outbox.publisher.routing-key", "%s")); var client = HttpClient.newHttpClient()) {
            var headers = headers(tenant);
            String body = "{\"partyId\":\"" + party + "\",\"code\":\"hu08\",\"operatingName\":\"NOMBRE\"}";
            var response = call(client, process.base, "/v1/companies", "POST", body, headers);
            assertEquals(201, response.statusCode(), response::body);
            var company = json.readTree(response.body()).path("data");
            var message = queue.message(); var wire = json.readTree(message.getBody());
            assertEquals(company.path("companyId").asText(), wire.path("aggregateId").asText());
            assertEquals(headers.get("Process-Id"), wire.path("correlationId").asText());
            awaitState(UUID.fromString(wire.path("eventId").asText()), "PUBLISHED:1:");
            assertEquals(201, call(client, process.base, "/v1/companies", "POST", body, headers).statusCode());
            var patchHeaders = new HashMap<>(headers); patchHeaders.remove("Idempotency-Key"); patchHeaders.put("If-Match", "0");
            assertEquals(200, call(client, process.base, "/v1/companies/" + company.path("companyId").asText(),
                    "PATCH", "{\"operatingName\":\"NOMBRE\"}", patchHeaders).statusCode());
            assertNull(queue.channel.basicGet(queue.name, true));
            try (var database = db(); var statement = database.createStatement(); var rows = statement.executeQuery(
                    "select count(*) from company_outbox_events where tenant_id='" + tenant + "'")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test void modesWithoutCredentialsKeepPendingIdentityUntilPublishedRestart(@TempDir Path work) throws Exception {
        var fact = seed("company.created.v1");
        var disabledTenant = UUID.randomUUID(); var disabledParty = UUID.randomUUID(); provider(disabledParty);
        var disabledHeaders = headers(disabledTenant);
        String disabledBody = "{\"partyId\":\"" + disabledParty + "\",\"code\":\"disabled\",\"operatingName\":\"DISABLED\"}";
        var rabbit = DeliveryEnvironmentResource.rabbit;
        try (var proxy = new AmqpFaultProxy(rabbit.getHost(), rabbit.getMappedPort(5672), AmqpFaultProxy.Mode.PASS)) {
        for (var mode : List.of("disabled", "stored-only")) {
            try (var process = new DeliveryProcess(work.resolve(mode), Map.of("company-registry.outbox.mode", mode,
                    "company-registry.outbox.broker.username", "", "company-registry.outbox.broker.password", "",
                    "mp.messaging.outgoing.company-outbox.username", "", "mp.messaging.outgoing.company-outbox.password", "",
                    "mp.messaging.outgoing.company-outbox.host", "127.0.0.1",
                    "mp.messaging.outgoing.company-outbox.port", Integer.toString(proxy.port())))) {
                assertEquals("PENDING:0:none", state(fact.eventId()));
                assertTrue(process.process.isAlive());
                assertFalse(java.nio.file.Files.readString(process.log).contains("outcome=CONFIRMED"));
                assertEquals(0, proxy.connections.get());
                try (var client = HttpClient.newHttpClient()) {
                    var metrics = client.send(HttpRequest.newBuilder(URI.create(process.base + "/q/metrics"))
                            .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, metrics.statusCode());
                    assertTrue(metrics.body().contains("company_outbox_pending NaN"));
                    assertTrue(metrics.body().contains("company_outbox_oldest_age_seconds NaN"));
                    assertTrue(metrics.body().contains("company_outbox_backlog_available 0.0"));
                    if (mode.equals("disabled")) {
                        var accepted = call(client, process.base, "/v1/companies", "POST", disabledBody, disabledHeaders);
                        assertEquals(201, accepted.statusCode(), accepted::body);
                    }
                }
                assertEquals(0, proxy.connections.get());
                assertEquals("PENDING:0:none", state(fact.eventId()));
            }
        }
        }
        try (var queue = new Queue("integration.company.created.v1"); var process = new DeliveryProcess(work.resolve("published"), Map.of())) {
            var message = queue.message();
            assertEquals(fact.eventId().toString(), message.getProps().getMessageId());
            assertEquals(fact.payload(), json.convertValue(json.readTree(message.getBody()).path("payload"), Map.class));
            awaitState(fact.eventId(), "PUBLISHED:1:");
            try (var client = HttpClient.newHttpClient()) {
                assertEquals(201, call(client, process.base, "/v1/companies", "POST", disabledBody, disabledHeaders).statusCode());
            }
            try (var database = db(); var statement = database.createStatement(); var rows = statement.executeQuery(
                    "select count(*) from company_outbox_events where tenant_id='" + disabledTenant + "'")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        }
    }

    private Map<String, String> proxy(AmqpFaultProxy proxy) {
        return Map.of("mp.messaging.outgoing.company-outbox.host", "127.0.0.1",
                "mp.messaging.outgoing.company-outbox.port", Integer.toString(proxy.port()),
                "company-registry.outbox.publisher.publish-timeout", "20s",
                "company-registry.outbox.publisher.claim-lease", "25s");
    }

    @Test void brokerConfirmIsRequiredAndControlledNackIsTerminalAtBudget(@TempDir Path work) throws Exception {
        var rabbit = DeliveryEnvironmentResource.rabbit;
        try (var queue = new Queue("integration.#"); var proxy = new AmqpFaultProxy(rabbit.getHost(), rabbit.getMappedPort(5672), AmqpFaultProxy.Mode.HOLD)) {
            var fact = seed("company.created.v1");
            try (var process = new DeliveryProcess(work.resolve("held"), proxy(proxy))) {
                assertTrue(proxy.confirmation.await(20, TimeUnit.SECONDS));
                assertEquals("PENDING:1:none", state(fact.eventId()));
                assertEquals(fact.eventId().toString(), queue.message().getProps().getMessageId());
                proxy.release(); awaitState(fact.eventId(), "PUBLISHED:1:");
                assertTrue(process.process.isAlive());
            }
        }
        try (var proxy = new AmqpFaultProxy(rabbit.getHost(), rabbit.getMappedPort(5672), AmqpFaultProxy.Mode.NACK)) {
            var fact = seed("company.created.v1");
            var settings = new HashMap<>(proxy(proxy)); settings.put("company-registry.outbox.publisher.maximum-attempts", "1");
            try (var process = new DeliveryProcess(work.resolve("nack"), settings)) {
                awaitState(fact.eventId(), "FAILED:1:broker-nack"); assertTrue(process.process.isAlive());
            }
        }
    }

    @Test void killedProcessAfterReservationRecoversIdenticalDuplicateOnLeaseExpiry(@TempDir Path work) throws Exception {
        var rabbit = DeliveryEnvironmentResource.rabbit;
        try (var queue = new Queue("integration.company.created.v1");
             var proxy = new AmqpFaultProxy(rabbit.getHost(), rabbit.getMappedPort(5672), AmqpFaultProxy.Mode.HOLD)) {
            var fact = seed("company.created.v1");
            var settings = new HashMap<>(proxy(proxy));
            settings.put("company-registry.outbox.publisher.publish-timeout", "1s");
            settings.put("company-registry.outbox.publisher.claim-lease", "3s");
            byte[] original;
            try (var first = new DeliveryProcess(work.resolve("killed"), settings)) {
                assertTrue(proxy.confirmation.await(10, TimeUnit.SECONDS));
                original = queue.message().getBody();
                first.kill(); assertFalse(first.process.isAlive());
            }
            try (var recovered = new DeliveryProcess(work.resolve("recovered"), Map.of())) {
                var duplicate = queue.message();
                assertArrayEquals(original, duplicate.getBody());
                assertEquals(fact.eventId().toString(), duplicate.getProps().getMessageId());
                awaitState(fact.eventId(), "PUBLISHED:2:");
                assertTrue(recovered.process.isAlive());
            }
        }
    }

    @Test void brokerConfirmedButLocalCommitMissingRecoversAfterRealProcessDeath(@TempDir Path work) throws Exception {
        var fact = seed("company.created.v1");
        sql("""
                create or replace function hu08_delay_published() returns trigger language plpgsql as $$
                begin if new.status='PUBLISHED' then perform pg_sleep(30); end if; return new; end; $$;
                create trigger hu08_delay_published before update on company_outbox_events
                for each row execute function hu08_delay_published();
                """);
        try (var queue = new Queue("integration.company.created.v1")) {
            byte[] original;
            try (var first = new DeliveryProcess(work.resolve("confirmed-killed"), Map.of())) {
                original = queue.message().getBody();
                assertEquals("PENDING:1:none", state(fact.eventId()));
                org.awaitility.Awaitility.await().atMost(WAIT).until(() -> java.nio.file.Files.readString(first.log).contains("outcome=CONFIRMED"));
                first.kill(); assertFalse(first.process.isAlive());
            } finally {
                // Test-only cancellation of the deliberately sleeping statement releases its row lock.
                try (var admin = DriverManager.getConnection(DeliveryEnvironmentResource.settings.get("quarkus.datasource.jdbc.url"),
                        DeliveryEnvironmentResource.settings.get("test.database.admin-user"),
                        DeliveryEnvironmentResource.settings.get("test.database.admin-password")); var statement = admin.createStatement()) {
                    statement.execute("select pg_cancel_backend(pid) from pg_stat_activity where query like 'update company_outbox_events set status=''PUBLISHED''%' and pid<>pg_backend_pid()");
                }
                sql("drop trigger hu08_delay_published on company_outbox_events; drop function hu08_delay_published()");
            }
            try (var second = new DeliveryProcess(work.resolve("confirmed-recovered"), Map.of())) {
                assertArrayEquals(original, queue.message().getBody());
                awaitState(fact.eventId(), "PUBLISHED:2:"); assertTrue(second.process.isAlive());
            }
        }
    }

    @Test void restartRecoversOnlyEligibleHistoryAndDoesNotBlockLaterAggregateRevision(@TempDir Path work) throws Exception {
        var pending = seed("company.created.v1");
        var expired = seed("company.updated.v1");
        var exhausted = seed("company.activated.v1");
        var failed = seed("company.deactivated.v1");
        var published = seed("company.site.created.v1");
        var backoff = seed("company.site.updated.v1");
        var last = seed("company.site.closed.v1");
        sql("update company_outbox_events set publish_attempts=1,last_attempt_at=now()-interval '1 minute',next_attempt_at=now()-interval '1 second' where id='" + expired.eventId() + "'");
        for (var fact : List.of(exhausted, last)) sql("update company_outbox_events set publish_attempts=2,last_attempt_at=now()-interval '1 minute',next_attempt_at=now()-interval '1 second' where id='" + fact.eventId() + "'");
        sql("update company_outbox_events set status='FAILED',last_error_code='broker-nack' where id='" + failed.eventId() + "'");
        sql("update company_outbox_events set status='PUBLISHED',published_at=now() where id='" + published.eventId() + "'");
        sql("update company_outbox_events set publish_attempts=1,last_error_code='broker-nack',next_attempt_at=now()+interval '1 hour' where id='" + backoff.eventId() + "'");
        var later = new StoredEvent(new UuidV7Generator().next(), backoff.tenantId(),
                new StoredEvent.Aggregate(backoff.aggregate().type(), backoff.aggregate().id(), backoff.aggregate().version()+1),
                backoff.kind(), backoff.context(), backoff.payload());
        insert(later);
        try (var process = new DeliveryProcess(work, Map.of("company-registry.outbox.publisher.maximum-attempts", "2"))) {
            awaitState(pending.eventId(), "PUBLISHED:1:"); awaitState(expired.eventId(), "PUBLISHED:2:");
            awaitState(exhausted.eventId(), "FAILED:2:delivery-outcome-unknown");
            awaitState(last.eventId(), "FAILED:2:delivery-outcome-unknown"); awaitState(later.eventId(), "PUBLISHED:1:");
            assertEquals("FAILED:0:broker-nack", state(failed.eventId()));
            assertEquals("PUBLISHED:0:none", state(published.eventId()));
            assertEquals("PENDING:1:broker-nack", state(backoff.eventId()));
            assertTrue(process.process.isAlive());
        }
    }

    @Test void initialBrokerOutageAndLossAfterConnectionKeepBusinessHealthyAndRecover(@TempDir Path work) throws Exception {
        var fact = seed("company.created.v1");
        brokerCommand("stop_app");
        try (var process = new DeliveryProcess(work, Map.of("company-registry.outbox.publisher.maximum-attempts", "100"));
             var client = HttpClient.newHttpClient()) {
            assertEquals(200, health(client, process.base));
            org.awaitility.Awaitility.await().atMost(WAIT).until(() -> !state(fact.eventId()).startsWith("PENDING:0:"));
            startBroker();
            awaitState(fact.eventId(), "PUBLISHED:");
            brokerCommand("stop_app");
            assertEquals(200, health(client, process.base));
            var tenant = UUID.randomUUID(); var party = UUID.randomUUID(); provider(party);
            var creation = call(client, process.base, "/v1/companies", "POST", "{\"partyId\":\"" + party
                    + "\",\"code\":\"down\",\"operatingName\":\"BROKER DOWN\"}", headers(tenant));
            assertEquals(201, creation.statusCode(), creation::body);
            assertEquals(200, health(client, process.base));
            startBroker();
            try (var database = db(); var statement = database.createStatement(); var rows = statement.executeQuery(
                    "select id from company_outbox_events where tenant_id='" + tenant + "'")) {
                assertTrue(rows.next());
                awaitState(rows.getObject(1, UUID.class), "PUBLISHED:");
            }
        } finally {
            startBroker();
            org.awaitility.Awaitility.await().atMost(WAIT).ignoreExceptions().until(() -> {
                try (var connection = DeliveryEnvironmentResource.connection()) { return connection.isOpen(); }
            });
        }
    }

    private static void startBroker() throws Exception { brokerCommand("start_app"); }

    private static void brokerCommand(String operation) throws Exception {
        var log = Path.of("build", "hu08-evidence", "broker-" + operation + ".log");
        java.nio.file.Files.createDirectories(log.getParent());
        // A fresh Podman CLI connection avoids stale Docker-compatible HTTP pool connections.
        var command = new ProcessBuilder("podman", "exec", DeliveryEnvironmentResource.rabbit.getContainerId(),
                "rabbitmqctl", operation).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!command.waitFor(30, TimeUnit.SECONDS)) { command.destroyForcibly(); throw new IllegalStateException("Broker fixture operation timed out"); }
        assertEquals(0, command.exitValue(), "Isolated broker operation; inspect " + log);
    }

    @Test void rejectedRequiredOutboxWriteRollsBackHttpBusinessAndCannotLeakToBroker(@TempDir Path work) throws Exception {
        var tenant = UUID.randomUUID(); var party = UUID.randomUUID(); provider(party);
        sql("""
                create or replace function hu08_reject_event() returns trigger language plpgsql as $$
                begin raise exception 'controlled required write failure' using errcode='40001'; end; $$;
                create trigger hu08_reject_event before insert on company_outbox_events
                for each row execute function hu08_reject_event();
                """);
        try (var queue = new Queue("integration.#"); var process = new DeliveryProcess(work, Map.of()); var client = HttpClient.newHttpClient()) {
            var response = call(client, process.base, "/v1/companies", "POST", "{\"partyId\":\"" + party
                    + "\",\"code\":\"rollback\",\"operatingName\":\"ROLLBACK\"}", headers(tenant));
            assertEquals(503, response.statusCode(), response::body);
            for (var table : List.of("companies", "company_idempotency_records", "company_outbox_events")) {
                try (var database = db(); var statement = database.createStatement(); var rows = statement.executeQuery(
                        "select count(*) from " + table + " where tenant_id='" + tenant + "'")) {
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
                }
            }
            assertNull(queue.channel.basicGet(queue.name, true));
        } finally { sql("drop trigger hu08_reject_event on company_outbox_events; drop function hu08_reject_event()"); }
    }

    private static int health(HttpClient client, String base) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/q/health/ready")).timeout(Duration.ofSeconds(3)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test void timeoutWithRealConfirmHeldPreservesFailedAfterLatePhysicalAck(@TempDir Path work) throws Exception {
        var rabbit = DeliveryEnvironmentResource.rabbit;
        try (var queue = new Queue("integration.company.created.v1");
             var proxy = new AmqpFaultProxy(rabbit.getHost(), rabbit.getMappedPort(5672), AmqpFaultProxy.Mode.HOLD)) {
            var fact = seed("company.created.v1");
            var settings = new HashMap<>(proxy(proxy));
            settings.put("company-registry.outbox.publisher.maximum-attempts", "1");
            settings.put("company-registry.outbox.publisher.publish-timeout", "500ms");
            settings.put("company-registry.outbox.publisher.claim-lease", "2s");
            try (var process = new DeliveryProcess(work, settings)) {
                assertTrue(proxy.confirmation.await(10, TimeUnit.SECONDS));
                assertEquals(fact.eventId().toString(), queue.message().getProps().getMessageId());
                awaitState(fact.eventId(), "FAILED:1:publish-timeout");
                proxy.release();
                org.awaitility.Awaitility.await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3))
                        .until(() -> state(fact.eventId()).equals("FAILED:1:publish-timeout"));
                assertEquals("FAILED:1:publish-timeout", state(fact.eventId()));
                assertTrue(process.process.isAlive());
            }
        }
    }

    private void provider(UUID party) {
        DeliveryEnvironmentResource.stub.reply("/v1/parties/" + party, 200, CompanyProviderFixtures.detail(party, "LEGAL_ENTITY", "ACTIVE"));
        DeliveryEnvironmentResource.stub.reply("/v1/parties/" + party + "/identifiers", 200,
                CompanyProviderFixtures.page(CompanyProviderFixtures.identifier(party, UUID.randomUUID(), "VERIFIED"), 1, null));
    }
    private static Map<String, String> headers(UUID tenant) {
        return Map.of("Tenant-Id", tenant.toString(), "User-Id", "Delivery-actor", "Process-Id", UUID.randomUUID().toString(),
                "Idempotency-Key", UUID.randomUUID().toString());
    }
    private static HttpResponse<String> call(HttpClient client, String base, String path, String method, String body,
            Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        headers.forEach(builder::header);
        return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
