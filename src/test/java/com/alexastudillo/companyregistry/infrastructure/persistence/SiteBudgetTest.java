package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.company.*;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.domain.model.*;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class SiteBudgetTest {
    @Inject
    AtomicOperation atomic;
    @Inject
    CompanyRepository companies;
    @Inject
    CompletionRepository completions;
    @Inject
    com.alexastudillo.companyregistry.application.site.SiteFingerprints fingerprints;
    @Inject
    com.alexastudillo.companyregistry.application.site.SiteResults results;
    @Inject
    com.alexastudillo.companyregistry.application.site.SiteCreationEvents events;
    @Inject
    OutboxRepository outbox;
    @Inject
    Identifiers ids;
    @Inject
    Clock clock;
    @Inject
    Flyway flyway;
    @Inject
    io.vertx.core.Vertx vertx;

    <T> CompletableFuture<T> invoke(Supplier<Uni<T>> operation) {
        var future = new CompletableFuture<T>();
        var context = io.smallrye.common.vertx.VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
        io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle.setContextSafe(context, true);
        context.runOnContext(_ -> {
            try {
                operation.get().subscribe().with(future::complete, future::completeExceptionally);
            } catch (Throwable f) {
                future.completeExceptionally(f);
            }
        });
        return future;
    }

    <T> T await(Supplier<Uni<T>> operation) throws Exception {
        return invoke(operation).get(10, TimeUnit.SECONDS);
    }

    CompanyRecord seed() throws Exception {
        var context = new OperationContext(UUID.randomUUID(), "Creator-aB", UUID.randomUUID().toString());
        var row = CompanyRegistration.candidate(
                new CompanyRegistration.Command(context, "creation", UUID.randomUUID(), "CODE", "COMPANY", null, null),
                clock, ids);
        return await(() -> atomic.execute(() -> companies.insert(row)));
    }

    @Inject
    SiteRepository sites;

    com.alexastudillo.companyregistry.application.site.SiteRegistration.Command command(CompanyRecord row) {
        return new com.alexastudillo.companyregistry.application.site.SiteRegistration.Command(
                new OperationContext(row.company().tenantId(), "Actor-aB", UUID.randomUUID().toString()),
                "budget", row.company().id(), "SITE", "SEDE", FieldChange.absent());
    }

    com.alexastudillo.companyregistry.application.site.RegisterSite service(AtomicOperation operation,
            Duration budget) {
        return new com.alexastudillo.companyregistry.application.site.RegisterSite(
                new com.alexastudillo.companyregistry.application.site.RegisterSite.Storage(operation, companies, sites,
                        events),
                new com.alexastudillo.companyregistry.application.site.RegisterSite.Idempotency(completions,
                        fingerprints, results),
                ids, clock, budget);
    }

    @Test
    void oneBudgetDecreasesAcrossLookupWriteAndFreshRecoverySession() throws Exception {
        var row = seed();
        var cmd = command(row);
        var checkpoints = new java.util.concurrent.CopyOnWriteArrayList<Duration>();
        var transactions = new java.util.concurrent.atomic.AtomicInteger();
        AtomicOperation measured = new AtomicOperation() {
            @Override
            public <T> Uni<T> execute(Supplier<Uni<T>> op) {
                return atomic.execute(op);
            }

            @Override
            public <T> Uni<T> read(Supplier<Uni<T>> op) {
                return atomic.read(op);
            }

            @Override
            public <T> Uni<T> read(Duration remaining, Supplier<Uni<T>> op) {
                checkpoints.add(remaining);
                transactions.incrementAndGet();
                return atomic.read(remaining, op);
            }

            @Override
            public Uni<Void> checkpoint(Duration remaining) {
                checkpoints.add(remaining);
                return atomic.checkpoint(remaining);
            }

            @Override
            public <T> Uni<T> execute(Duration remaining, Supplier<Uni<T>> op) {
                checkpoints.add(remaining);
                transactions.incrementAndGet();
                return atomic.execute(remaining, op).chain(_ -> Uni.createFrom()
                        .failure(new PersistenceFailure(PersistenceFailure.Kind.UNAVAILABLE, null)));
            }
        };
        assertEquals(0, await(() -> service(measured, Duration.ofSeconds(5)).execute(cmd))
                .site().site().version().value());
        assertEquals(3, transactions.get());
        assertTrue(checkpoints.size() > 5);
        for (int i = 1; i < checkpoints.size(); i++)
            assertTrue(checkpoints.get(i).compareTo(checkpoints.get(i - 1)) <= 0, checkpoints.toString());
        assertTrue(checkpoints.getLast().compareTo(Duration.ofSeconds(5)) < 0);
    }

    long count(String table, UUID tenant) throws Exception {
        try (var c = flyway.getConfiguration().getDataSource().getConnection();
                var st = c.createStatement();
                var r = st.executeQuery("SELECT count(*) FROM " + table + " WHERE tenant_id='" + tenant + "'")) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void validatesPositiveFiniteBudget() {
        for (var value : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(Long.MAX_VALUE)))
            assertThrows(IllegalArgumentException.class, () -> service(atomic, value));
    }

    @Test
    void exhaustedLockWaitDoesNotConsumeTheKey() throws Exception {
        var row = seed();
        var command = command(row);
        try (var holder = flyway.getConfiguration().getDataSource().getConnection();
                var st = holder.createStatement()) {
            holder.setAutoCommit(false);
            st.execute("SELECT id FROM companies WHERE id='" + row.company().id() + "' FOR UPDATE");
            var failure = assertThrows(ExecutionException.class,
                    () -> await(() -> service(atomic, Duration.ofMillis(250)).execute(command)));
            assertInstanceOf(com.alexastudillo.companyregistry.application.site.SiteFailure.class, failure.getCause());
            assertEquals(com.alexastudillo.companyregistry.application.site.SiteFailure.Kind.UNAVAILABLE,
                    ((com.alexastudillo.companyregistry.application.site.SiteFailure) failure.getCause()).kind());
            holder.rollback();
        }
        assertEquals(0, count("company_sites", row.company().tenantId()));
        assertEquals(0, count("company_idempotency_records", row.company().tenantId()));
        assertEquals(0,
                await(() -> service(atomic, Duration.ofSeconds(5)).execute(command)).site().site().version().value());
    }

    @Test
    void cancellationRollsBackTheSameSubscriptionWithoutDetachedWrites() throws Exception {
        var row = seed();
        var command = command(row);
        var reached = new CompletableFuture<Void>();
        com.alexastudillo.companyregistry.application.site.SiteCreationEvents pause = event -> {
            reached.complete(null);
            return Uni.createFrom().nothing();
        };
        var service = new com.alexastudillo.companyregistry.application.site.RegisterSite(
                new com.alexastudillo.companyregistry.application.site.RegisterSite.Storage(atomic, companies, sites,
                        pause),
                new com.alexastudillo.companyregistry.application.site.RegisterSite.Idempotency(completions,
                        fingerprints, results),
                ids, clock, Duration.ofSeconds(5));
        var context = io.smallrye.common.vertx.VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
        io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle.setContextSafe(context, true);
        var subscription = new CompletableFuture<io.smallrye.mutiny.subscription.Cancellable>();
        context.runOnContext(_ -> subscription.complete(service.execute(command).subscribe().with(_ -> {
        }, _ -> {
        })));
        var active = subscription.get(5, TimeUnit.SECONDS);
        reached.get(5, TimeUnit.SECONDS);
        context.runOnContext(_ -> active.cancel());
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
            try (var c = flyway.getConfiguration().getDataSource().getConnection(); var st = c.createStatement()) {
                st.execute("SELECT id FROM companies WHERE id='" + row.company().id() + "' FOR UPDATE NOWAIT");
                return true;
            } catch (java.sql.SQLException failure) {
                if ("55P03".equals(failure.getSQLState()))
                    return false;
                throw failure;
            }
        });
        assertEquals(0, count("company_sites", row.company().tenantId()));
        assertEquals(0, count("company_idempotency_records", row.company().tenantId()));
        assertEquals(0,
                await(() -> service(atomic, Duration.ofSeconds(5)).execute(command)).site().site().version().value());
    }

    @Test
    void deadlineRollsBackUnfinishedWorkWithoutASeparateWriteSubscription() throws Exception {
        var row = seed();
        var cmd = command(row);
        var subscriptions = new java.util.concurrent.atomic.AtomicInteger();
        com.alexastudillo.companyregistry.application.site.SiteCreationEvents unfinished = event ->
                Uni.createFrom().deferred(() -> {
                    subscriptions.incrementAndGet();
                    return Uni.createFrom().nothing();
                });
        var registration = new com.alexastudillo.companyregistry.application.site.RegisterSite(
                new com.alexastudillo.companyregistry.application.site.RegisterSite.Storage(atomic, companies, sites, unfinished),
                new com.alexastudillo.companyregistry.application.site.RegisterSite.Idempotency(completions, fingerprints, results),
                ids, clock, Duration.ofMillis(500));
        var pending = invoke(() -> registration.execute(cmd));
        var failure = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
        assertEquals(com.alexastudillo.companyregistry.application.site.SiteFailure.Kind.UNAVAILABLE,
                ((com.alexastudillo.companyregistry.application.site.SiteFailure) failure.getCause()).kind());
        assertEquals(1, subscriptions.get());
        assertEquals(0, count("company_sites", row.company().tenantId()));
        assertEquals(0, count("company_idempotency_records", row.company().tenantId()));
        assertEquals(0, count("company_outbox_events", row.company().tenantId()));
        var accepted = await(() -> service(atomic, Duration.ofSeconds(5)).execute(cmd));
        assertEquals(0, accepted.site().site().version().value());
    }

    @Test
    void uncertainFinalizationIsBoundedAndARetryReplaysTheCommittedAcceptance() throws Exception {
        var row = seed();
        var cmd = command(row);
        var committed = new java.util.concurrent.atomic.AtomicReference<com.alexastudillo.companyregistry.application.site.SiteRegistration.Result>();
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        AtomicOperation missingConfirmation = new AtomicOperation() {
            @Override public <T> Uni<T> execute(Supplier<Uni<T>> work) { return atomic.execute(work); }
            @Override public <T> Uni<T> read(Supplier<Uni<T>> work) { return atomic.read(work); }
            @Override public <T> Uni<T> read(Duration timeout, Supplier<Uni<T>> work) { return atomic.read(timeout, work); }
            @Override public Uni<Void> checkpoint(Duration remaining) { return atomic.checkpoint(remaining); }
            @Override public <T> Uni<T> execute(Duration timeout, Supplier<Uni<T>> work) {
                writes.incrementAndGet();
                return atomic.execute(timeout, work).invoke(value -> committed.set(
                        (com.alexastudillo.companyregistry.application.site.SiteRegistration.Result) value))
                        .chain(_ -> Uni.createFrom().nothing());
            }
        };
        var pending = invoke(() -> service(missingConfirmation, Duration.ofMillis(500)).execute(cmd));
        var failure = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
        assertEquals(com.alexastudillo.companyregistry.application.site.SiteFailure.Kind.UNAVAILABLE,
                ((com.alexastudillo.companyregistry.application.site.SiteFailure) failure.getCause()).kind());
        assertNotNull(committed.get());
        assertEquals(1, writes.get());
        assertEquals(1, count("company_sites", row.company().tenantId()));
        assertEquals(1, count("company_idempotency_records", row.company().tenantId()));
        var replay = await(() -> service(atomic, Duration.ofSeconds(5)).execute(cmd));
        assertEquals(committed.get(), replay);
        assertEquals(1, count("company_sites", row.company().tenantId()));
        assertEquals(1, count("company_idempotency_records", row.company().tenantId()));
    }

    @Test
    void deadlineBoundsARealCommitBlockedByADeferredTrigger() throws Exception {
        var row = seed();
        var cmd = command(row);
        var name = "hu04_commit_" + UUID.randomUUID().toString().replace("-", "");
        long lockKey = UUID.randomUUID().getMostSignificantBits();
        try (var ddl = flyway.getConfiguration().getDataSource().getConnection();
                var statement = ddl.createStatement();
                var holder = flyway.getConfiguration().getDataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, lockKey);
                lock.execute();
            }
            statement.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "IF NEW.tenant_id='" + row.company().tenantId() + "'::uuid THEN "
                    + "PERFORM pg_advisory_xact_lock(" + lockKey + "); END IF; RETURN NEW; END $$");
            statement.execute("CREATE CONSTRAINT TRIGGER " + name
                    + " AFTER INSERT ON company_sites DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION "
                    + name + "()");
            try {
                var pending = invoke(() -> service(atomic, Duration.ofSeconds(2)).execute(cmd));
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1)).until(() -> {
                    try (var waiting = ddl.createStatement(); var result = waiting.executeQuery(
                            "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted")) {
                        result.next();
                        return result.getInt(1) > 0;
                    }
                });
                var failure = assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
                assertEquals(com.alexastudillo.companyregistry.application.site.SiteFailure.Kind.UNAVAILABLE,
                        ((com.alexastudillo.companyregistry.application.site.SiteFailure) failure.getCause()).kind());
            } finally {
                holder.rollback();
                statement.execute("DROP TRIGGER " + name + " ON company_sites");
                statement.execute("DROP FUNCTION " + name + "()");
            }
        }
        // The timed-out COMMIT is uncertain; retry the key rather than asserting rollback.
        var accepted = await(() -> service(atomic, Duration.ofSeconds(5)).execute(cmd));
        var replay = await(() -> service(atomic, Duration.ofSeconds(5)).execute(cmd));
        assertEquals(accepted, replay);
        assertEquals(1, count("company_sites", row.company().tenantId()));
        assertEquals(1, count("company_idempotency_records", row.company().tenantId()));
    }

    @Test
    void postgresTransientAbortsRollbackAndTheSameKeyCanRetry() throws Exception {
        for (var state : List.of("40001", "40P01")) {
            var row = seed();
            var cmd = command(row);
            var name = "hu04_abort_" + UUID.randomUUID().toString().replace("-", "");
            try (var c = flyway.getConfiguration().getDataSource().getConnection(); var st = c.createStatement()) {
                st.execute(
                        "CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"
                                + row.company().tenantId()
                                + "'::uuid THEN RAISE EXCEPTION 'injected transient abort' USING ERRCODE='" + state
                                + "'; END IF; RETURN NEW; END $$");
                st.execute("CREATE TRIGGER " + name
                        + " BEFORE INSERT ON company_idempotency_records FOR EACH ROW EXECUTE FUNCTION " + name + "()");
                try {
                    var failure = assertThrows(ExecutionException.class, () -> await(
                            () -> service(atomic, Duration.ofSeconds(5)).execute(cmd)));
                    assertEquals(com.alexastudillo.companyregistry.application.site.SiteFailure.Kind.UNAVAILABLE,
                            ((com.alexastudillo.companyregistry.application.site.SiteFailure) failure.getCause())
                                    .kind());
                    assertEquals(row, await(
                            () -> atomic.read(() -> companies.find(row.company().tenantId(), row.company().id()))));
                    assertEquals(0, count("company_idempotency_records", row.company().tenantId()));
                    assertEquals(0, count("company_outbox_events", row.company().tenantId()));
                } finally {
                    st.execute("DROP TRIGGER " + name + " ON company_idempotency_records");
                    st.execute("DROP FUNCTION " + name + "()");
                }
            }
            assertEquals(0, await(() -> service(atomic, Duration.ofSeconds(5)).execute(cmd))
                    .site().site().version().value());
        }
    }

}
