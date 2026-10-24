package com.alexastudillo.companyregistry.infrastructure.outbox;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.outbox.*;
import com.alexastudillo.companyregistry.application.persistence.OutboxRepository;
import com.alexastudillo.companyregistry.contract.DeliveryEnvironmentResource;
import com.alexastudillo.companyregistry.contract.OutboxFixtures;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(DeliveryIntegrationTest.Profile.class)
@QuarkusTestResource(value = DeliveryEnvironmentResource.class, restrictToAnnotatedClass = true)
class DeliveryIntegrationTest {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() { return Map.of("quarkus.scheduler.enabled", "false"); }
    }
    @Inject DeliveryStore store;
    @Inject OutboxRepository repository;
    @Inject DeliveryContexts contexts;
    @Inject OutboxScheduler scheduler;
    @Inject io.micrometer.core.instrument.MeterRegistry metrics;
    @Inject com.alexastudillo.companyregistry.application.persistence.AtomicOperation atomic;

    private <T> T run(java.util.function.Supplier<Uni<T>> work) {
        return contexts.isolated(work).await().atMost(Duration.ofSeconds(15));
    }
    private void sql(String sql) { run(() -> Panache.withTransaction(() -> Panache.getSession()
            .chain(s -> s.createNativeQuery(sql).executeUpdate()))); }
    @BeforeEach void clearIsolatedOutbox() { sql("delete from company_outbox_events"); }
    private void insert(String type) {
        var example = OutboxFixtures.example(type);
        var fact = new StoredEvent(new com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator().next(),
                example.tenantId(), example.aggregate(), example.kind(), example.context(), example.payload());
        var row = OutboxFixtures.row(fact);
        run(() -> Panache.withTransaction(() -> repository.insert(row)));
    }

    @Test void postgresReservationsExcludeEachOtherAndRejectDuplicateOrOldResults() throws Exception {
        for (var type : OutboxDestination.EVENT_TYPES) insert(type);
        var first = contexts.isolated(() -> store.reserve(3, 10, Duration.ofSeconds(30))).subscribeAsCompletionStage();
        var second = contexts.isolated(() -> store.reserve(3, 10, Duration.ofSeconds(30))).subscribeAsCompletionStage();
        var a = first.toCompletableFuture().get(15, TimeUnit.SECONDS);
        var b = second.toCompletableFuture().get(15, TimeUnit.SECONDS);
        assertEquals(3, a.claims().size()); assertEquals(3, b.claims().size());
        assertTrue(java.util.Collections.disjoint(a.claims().stream().map(c -> c.event().eventId()).toList(),
                b.claims().stream().map(c -> c.event().eventId()).toList()));
        var remaining = run(() -> store.reserve(25, 10, Duration.ofSeconds(30)));
        assertEquals(1, remaining.claims().size());
        var r = a.claims().getFirst().reservation();
        assertTrue(run(() -> store.finish(r, DeliveryStore.Outcome.RETRY, Duration.ofSeconds(1), DeliveryFailure.Code.BROKER_NACK)));
        assertFalse(run(() -> store.finish(r, DeliveryStore.Outcome.PUBLISHED, Duration.ZERO, null)));
        var stored = run(() -> Panache.withSession(() -> repository.find(r.tenantId(), r.eventId())));
        assertEquals(r.technicalVersion() + 1, stored.version());
        assertEquals("outbox-publisher", stored.audit().updatedBy());
        assertEquals(a.claims().getFirst().event(), StoredEvent.from(stored));
        sql("update company_outbox_events set next_attempt_at=clock_timestamp()-interval '1 second' where id='" + r.eventId() + "'");
        var newer = run(() -> store.reserve(1, 10, Duration.ofSeconds(30))).claims().getFirst();
        assertEquals(2, newer.reservation().attempt());
        assertFalse(run(() -> store.finish(r, DeliveryStore.Outcome.PUBLISHED, Duration.ZERO, null)));
        assertTrue(run(() -> store.finish(newer.reservation(), DeliveryStore.Outcome.PUBLISHED, Duration.ZERO, null)));
    }

    @Test void lastExpiredAttemptBecomesFailedWithoutReservingMaximumPlusOne() {
        insert("company.created.v1");
        var batch = run(() -> store.reserve(1, 1, Duration.ofNanos(1)));
        assertEquals(1, batch.claims().getFirst().reservation().attempt());
        var exhausted = run(() -> store.reserve(1, 1, Duration.ofSeconds(30)));
        assertEquals(1, exhausted.exhausted()); assertTrue(exhausted.claims().isEmpty());
        var r = batch.claims().getFirst().reservation();
        var failed = run(() -> Panache.withSession(() -> repository.find(r.tenantId(), r.eventId())));
        assertEquals("FAILED", failed.status().name()); assertEquals(1, failed.attempts());
        assertEquals("delivery-outcome-unknown", failed.errorCode());
        assertFalse(run(() -> store.finish(r, DeliveryStore.Outcome.PUBLISHED, Duration.ZERO, null)));
        assertTrue(run(() -> store.reserve(25, 100, Duration.ofSeconds(30))).claims().isEmpty());
    }

    @Test void realBrokerReceivesAllSevenPersistentContractsAndNoConsumerIsRequired() throws Exception {
        try (var connection = DeliveryEnvironmentResource.connection(); var channel = connection.createChannel()) {
            channel.exchangeDeclare("company.events", "topic", true);
            String queue = channel.queueDeclare("hu08-" + java.util.UUID.randomUUID(), true, false, false, null).getQueue();
            try {
                channel.queueBind(queue, "company.events", "integration.#");
                double before = metrics.find("company.outbox.delivery").tag("outcome", "published").counters().stream()
                        .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
                for (var type : OutboxDestination.EVENT_TYPES) insert(type);
                run(scheduler::poll);
                assertEquals(0, run(store::backlog).pending());
                var received = new java.util.HashSet<String>();
                for (int i = 0; i < 7; i++) {
                    var message = channel.basicGet(queue, true);
                    assertNotNull(message);
                    var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(message.getBody());
                    received.add(body.path("eventType").asText());
                    assertEquals(11, body.size());
                    assertEquals(body.path("eventId").asText(), message.getProps().getMessageId());
                    assertEquals(2, message.getProps().getDeliveryMode());
                    assertEquals("application/json", message.getProps().getContentType());
                    assertEquals("integration." + body.path("eventType").asText(), message.getEnvelope().getRoutingKey());
                    assertEquals(body.path("correlationId").asText(), message.getProps().getCorrelationId());
                }
                assertEquals(OutboxDestination.EVENT_TYPES, received);
                assertEquals(before + 7, metrics.find("company.outbox.delivery").tag("outcome", "published").counters().stream()
                        .mapToDouble(io.micrometer.core.instrument.Counter::count).sum());
            } finally { channel.queueDelete(queue); }
        }
    }

    @Test void confirmWithoutBindingCanBePublishedAndFutureQueueCannotRecoverMessage() throws Exception {
        try (var connection = DeliveryEnvironmentResource.connection(); var channel = connection.createChannel()) {
            channel.exchangeDeclare("company.events", "topic", true);
            insert("company.created.v1"); run(scheduler::poll);
            assertEquals(0, run(store::backlog).pending());
            String queue = channel.queueDeclare().getQueue();
            channel.queueBind(queue, "company.events", "integration.#");
            assertNull(channel.basicGet(queue, true));
        }
    }

    @Test void reducedBudgetPreservesKnownDiagnosticAndTerminalHistory() {
        insert("company.created.v1");
        var reservation = run(() -> store.reserve(1, 10, Duration.ofSeconds(30))).claims().getFirst().reservation();
        assertTrue(run(() -> store.finish(reservation, DeliveryStore.Outcome.RETRY, Duration.ofNanos(1), DeliveryFailure.Code.BROKER_NACK)));
        var batch = run(() -> store.reserve(1, 1, Duration.ofSeconds(30)));
        assertEquals(1, batch.exhausted());
        var failed = run(() -> Panache.withSession(() -> repository.find(reservation.tenantId(), reservation.eventId())));
        assertEquals("broker-nack", failed.errorCode()); assertEquals(1, failed.attempts());
        assertTrue(run(() -> store.reserve(1, 100, Duration.ofSeconds(30))).claims().isEmpty());
    }

    @Test void independentLockerDoesNotBlockOtherReservationAndRollbackReleasesOwnership() throws Exception {
        insert("company.created.v1"); insert("company.updated.v1");
        var locked = new java.util.concurrent.CompletableFuture<Void>();
        var release = new java.util.concurrent.CompletableFuture<Void>();
        var owner = contexts.isolated(() -> Panache.withTransaction(() -> Panache.getSession()
                .chain(s -> s.createNativeQuery("select id from company_outbox_events where event_type='company.created.v1' for update")
                        .getSingleResult()).invoke(() -> locked.complete(null))
                .chain(() -> Uni.createFrom().completionStage(release))
                .chain(() -> Uni.createFrom().failure(new IllegalStateException("controlled-rollback")))))
                .subscribeAsCompletionStage();
        locked.get(10, TimeUnit.SECONDS);
        try {
            var other = run(() -> store.reserve(25, 10, Duration.ofSeconds(30)));
            assertEquals(1, other.claims().size());
            assertEquals("company.updated.v1", other.claims().getFirst().event().kind().type());
        } finally { release.complete(null); }
        var rolledBack = owner.toCompletableFuture();
        assertThrows(java.util.concurrent.ExecutionException.class, () -> rolledBack.get(10, TimeUnit.SECONDS));
        var recovered = run(() -> store.reserve(25, 10, Duration.ofSeconds(30)));
        assertEquals(1, recovered.claims().size());
        assertEquals("company.created.v1", recovered.claims().getFirst().event().kind().type());
    }

    @Test void evaluatesExistingDeliveryIndexWithTwentyThousandPendingRows() throws Exception {
        sql("""
                insert into company_outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,payload,
                    correlation_id,created_by,updated_by,created_at,updated_at,next_attempt_at)
                select uuidv7(),uuidv7(),'COMPANY',uuidv7(),1,'company.updated.v1','{}',uuidv7()::text,'plan-test','plan-test',
                    now()-interval '1 day',now(),case when n%2=0 then now()-interval '1 hour' else null end
                from generate_series(1,20000) n
                """);
        try (var database = java.sql.DriverManager.getConnection(DeliveryEnvironmentResource.settings.get("quarkus.datasource.jdbc.url"),
                "company_registry_migration", "migration_test"); var statement = database.createStatement()) {
            statement.execute("analyze company_outbox_events");
        }
        List<String> plan = run(() -> Panache.withTransaction(() -> Panache.getSession().chain(s -> s.createNativeQuery("""
                explain (analyze, buffers) select * from company_outbox_events
                where status='PENDING' and (next_attempt_at is null or next_attempt_at<=now())
                order by coalesce(next_attempt_at,created_at),created_at,id limit 25 for update skip locked
                """, String.class).getResultList())));
        assertTrue(plan.stream().anyMatch(line -> line.contains("Execution Time:")));
        assertTrue(plan.stream().anyMatch(line -> line.contains("rows=25")));
        var path = java.nio.file.Path.of("build/hu08-evidence/postgres-delivery-plan.txt");
        java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.write(path, plan);
        try (var database = java.sql.DriverManager.getConnection(DeliveryEnvironmentResource.settings.get("quarkus.datasource.jdbc.url"),
                "company_registry_migration", "migration_test"); var statement = database.createStatement()) {
            database.setAutoCommit(false);
            statement.execute("drop index ix_company_outbox_due_priority");
            var baseline = new java.util.ArrayList<String>();
            try (var rows = statement.executeQuery("""
                    explain (analyze, buffers) select * from company_outbox_events
                    where status='PENDING' and (next_attempt_at is null or next_attempt_at<=now())
                    order by coalesce(next_attempt_at,created_at),created_at,id limit 25 for update skip locked
                    """)) { while (rows.next()) baseline.add(rows.getString(1)); }
            database.rollback();
            java.nio.file.Files.write(path.resolveSibling("postgres-delivery-plan-v8.txt"), baseline);
        }
    }

    @Test void invalidFactFailsWithoutSendingOrCancellingValidSibling() throws Exception {
        var e = OutboxFixtures.example("company.created.v1");
        var payload = new java.util.HashMap<>(e.payload()); payload.put("email", "private-payload-secret");
        var bad = new StoredEvent(new com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator().next(),
                e.tenantId(), e.aggregate(), e.kind(), e.context(), payload);
        run(() -> Panache.withTransaction(() -> repository.insert(OutboxFixtures.row(bad))));
        insert("company.updated.v1");
        try (var connection = DeliveryEnvironmentResource.connection(); var channel = connection.createChannel()) {
            channel.exchangeDeclare("company.events", "topic", true);
            var queue = channel.queueDeclare().getQueue(); channel.queueBind(queue, "company.events", "integration.#");
            run(scheduler::poll);
            var failed = run(() -> Panache.withSession(() -> repository.find(bad.tenantId(), bad.eventId())));
            assertEquals("FAILED", failed.status().name()); assertEquals("invalid-contract", failed.errorCode());
            assertEquals("invalid-contract", failed.errorDetail()); assertEquals(1, failed.attempts());
            var message = channel.basicGet(queue, true);
            assertNotNull(message); assertEquals("company.updated.v1", message.getProps().getType());
            assertNull(channel.basicGet(queue, true));
        }
    }

    @Test void dueRetryHasPriorityOverNewInsertions() {
        insert("company.created.v1"); insert("company.updated.v1");
        sql("update company_outbox_events set created_at=clock_timestamp(),updated_at=clock_timestamp() where event_type='company.created.v1'");
        sql("update company_outbox_events set next_attempt_at=clock_timestamp()-interval '1 hour',last_attempt_at=clock_timestamp()-interval '2 hours',publish_attempts=1 where event_type='company.updated.v1'");
        var first = run(() -> store.reserve(1, 10, Duration.ofSeconds(30)));
        assertEquals("company.updated.v1", first.claims().getFirst().event().kind().type());
        assertEquals(2, first.claims().getFirst().reservation().attempt());
    }

    @Test void cancellationRollsBackUncommittedReservationAndReleasesItsLock() throws Exception {
        insert("company.created.v1");
        var reserved = new java.util.concurrent.CompletableFuture<Void>();
        var subscription = contexts.isolated(() -> atomic.execute(() -> store.reserve(1, 10, Duration.ofSeconds(30))
                .invoke(() -> reserved.complete(null)).chain(() -> Uni.createFrom().nothing())))
                .subscribe().with(ignored -> {}, ignored -> {});
        try {
            reserved.get(10, TimeUnit.SECONDS);
            assertTrue(run(() -> store.reserve(1, 10, Duration.ofSeconds(30))).claims().isEmpty());
        } finally { subscription.cancel(); }
        var recovered = new java.util.concurrent.atomic.AtomicReference<DeliveryStore.Batch>();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
            recovered.set(run(() -> store.reserve(1, 10, Duration.ofSeconds(30))));
            return !recovered.get().claims().isEmpty();
        });
        assertEquals(1, recovered.get().claims().getFirst().reservation().attempt());
        assertEquals(1, recovered.get().claims().getFirst().reservation().technicalVersion());
    }
}
