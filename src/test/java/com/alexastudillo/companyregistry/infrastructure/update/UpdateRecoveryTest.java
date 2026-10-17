package com.alexastudillo.companyregistry.infrastructure.update;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.company.*;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.domain.model.*;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import com.alexastudillo.companyregistry.infrastructure.persistence.*;
import com.alexastudillo.companyregistry.infrastructure.outbox.*;
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
class UpdateRecoveryTest {
    @Inject
    AtomicOperation atomic;
    @Inject
    CompanyRepository companies;
    @Inject
    CompletionRepository completions;
    @Inject
    UpdateFingerprints fingerprints;
    @Inject
    UpdateResults results;
    @Inject
    UpdateEvents events;
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

    CompanyUpdate.Command command(CompanyRecord row, String key) {
        return new CompanyUpdate.Command(
                new OperationContext(row.company().tenantId(), "Updater-aB", UUID.randomUUID().toString()),
                row.company().id(), 0, key,
                new CompanyInformationPatch(FieldChange.absent(), FieldChange.present("NEW"), FieldChange.absent(),
                        FieldChange.absent()));
    }

    UpdateCompany service(AtomicOperation a, CompanyRepository c, CompletionRepository completions, UpdateEvents e,
            UpdateFingerprints f) {
        return new UpdateCompany(new UpdateCompany.Storage(a, c, e),
                new UpdateCompany.Idempotency(completions, f, results), ids, clock, Duration.ofSeconds(5));
    }

    OutboxConfiguration configuration(String mode) {
        return new io.smallrye.config.SmallRyeConfigBuilder()
                .withConverter(Duration.class, 100, new io.quarkus.runtime.configuration.DurationConverter())
                .withMapping(OutboxConfiguration.class).withDefaultValue("company-registry.outbox.mode", mode).build()
                .getConfigMapping(OutboxConfiguration.class);
    }

    long count(String table, UUID tenant) throws Exception {
        try (var c = flyway.getConfiguration().getDataSource().getConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT count(*) FROM " + table + " WHERE tenant_id='" + tenant + "'")) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void everyOutboxModeWorksWithAndWithoutKeyAndReplayIgnoresLaterMode() throws Exception {
        for (var mode : List.of("disabled", "stored-only", "published"))
            for (var key : Arrays.asList(null, "key")) {
                var row = seed();
                var cmd = command(row, key);
                var event = new CompanyUpdatedOutbox(new OutboxPolicy(configuration(mode), outbox));
                var accepted = await(() -> service(atomic, companies, completions, event, fingerprints).execute(cmd));
                assertEquals(1, accepted.company().version().value());
                assertEquals(row.audit().createdAt(), accepted.audit().createdAt());
                assertEquals(mode.equals("disabled") ? 0 : 1, count("company_outbox_events", row.company().tenantId()));
                assertEquals(key == null ? 0 : 1, count("company_idempotency_records", row.company().tenantId()));
                if (!mode.equals("disabled")) {
                    try (var c = flyway.getConfiguration().getDataSource().getConnection();
                            var st = c.createStatement();
                            var resultSet = st.executeQuery("SELECT * FROM company_outbox_events WHERE tenant_id='"
                                    + row.company().tenantId() + "'")) {
                        assertTrue(resultSet.next());
                        assertEquals("PENDING", resultSet.getString("status"));
                        assertEquals(0, resultSet.getInt("publish_attempts"));
                        assertEquals(0, resultSet.getLong("version"));
                        assertEquals(accepted.audit().updatedAt(), resultSet.getTimestamp("occurred_at").toInstant());
                        assertEquals("Updater-aB", resultSet.getString("created_by"));
                        assertEquals("Updater-aB", resultSet.getString("updated_by"));
                        assertNull(resultSet.getString("causation_id"));
                        assertNull(resultSet.getTimestamp("published_at"));
                        assertFalse(resultSet.next());
                    }
                }
                if (key != null)
                    assertEquals(accepted, await(() -> service(atomic, companies, completions,
                            _ -> Uni.createFrom().failure(new AssertionError("replay-must-not-write")), fingerprints)
                            .execute(cmd)));
            }
    }

    @SuppressWarnings("unchecked")
    <T> T failAfter(T target, Class<T> port, String method) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] { port },
                (_, m, args) -> {
                    Object value;
                    try {
                        value = m.invoke(target, args);
                    } catch (java.lang.reflect.InvocationTargetException f) {
                        throw f.getCause();
                    }
                    return m.getName().equals(method)
                            ? ((Uni<?>) value).chain(
                                    _ -> Uni.createFrom().failure(new IllegalStateException("injected-write-failure")))
                            : value;
                });
    }

    @Test
    void failuresAfterEachRequiredWriteRollbackEveryEffectAndLeaveTheKeyReusable() throws Exception {
        var event = new CompanyUpdatedOutbox(new OutboxPolicy(configuration("stored-only"), outbox));
        for (var stage : List.of("company", "completion", "event")) {
            var row = seed();
            var cmd = command(row, "rollback");
            var c = stage.equals("company") ? failAfter(companies, CompanyRepository.class, "update") : companies;
            var completion = stage.equals("completion") ? failAfter(completions, CompletionRepository.class, "insert")
                    : completions;
            UpdateEvents e = stage.equals("event")
                    ? candidate -> event.store(candidate)
                            .chain(_ -> Uni.createFrom().failure(new IllegalStateException("event-failure")))
                    : event;
            var failure = assertThrows(ExecutionException.class,
                    () -> await(() -> service(atomic, c, completion, e, fingerprints).execute(cmd)));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(row,
                    await(() -> atomic.read(() -> companies.find(row.company().tenantId(), row.company().id()))));
            assertEquals(0, count("company_idempotency_records", row.company().tenantId()));
            assertEquals(0, count("company_outbox_events", row.company().tenantId()));
            assertEquals(1, await(() -> service(atomic, companies, completions, event, fingerprints).execute(cmd))
                    .company().version().value());
        }
    }

    AtomicOperation lostResponse() {
        return new AtomicOperation() {
            @Override
            public <T> Uni<T> execute(Supplier<Uni<T>> op) {
                return atomic.execute(op);
            }

            @Override
            public <T> Uni<T> read(Supplier<Uni<T>> op) {
                return atomic.read(op);
            }

            @Override
            public <T> Uni<T> read(Duration limit, Supplier<Uni<T>> op) {
                return atomic.read(limit, op);
            }

            @Override
            public Uni<Void> checkpoint(Duration limit) {
                return atomic.checkpoint(limit);
            }

            @Override
            public <T> Uni<T> execute(Duration limit, Supplier<Uni<T>> op) {
                return atomic.execute(limit, op).chain(_ -> Uni.createFrom()
                        .failure(new PersistenceFailure(PersistenceFailure.Kind.UNAVAILABLE, null)));
            }
        };
    }

    @Test
    void resolvesActualCommitWithKeyAndDoesNotReexecuteUnkeyedUncertainty() throws Exception {
        for (var key : Arrays.asList(null, "lost")) {
            var row = seed();
            var cmd = command(row, key);
            var service = service(lostResponse(), companies, completions, events, fingerprints);
            if (key == null) {
                var failure = assertThrows(ExecutionException.class, () -> await(() -> service.execute(cmd)));
                assertEquals(CompanyFailure.Kind.UNAVAILABLE, ((CompanyFailure) failure.getCause()).kind());
            } else {
                var accepted = await(() -> service.execute(cmd));
                assertEquals(accepted,
                        await(() -> service(atomic, companies, completions, events, fingerprints).execute(cmd)));
            }
            assertEquals(1, await(() -> atomic.read(() -> companies.find(row.company().tenantId(), row.company().id())))
                    .company().version().value());
        }
    }

    long blocked() throws Exception {
        try (var c = flyway.getConfiguration().getDataSource().getConnection();
                var s = c.createStatement();
                var r = s.executeQuery(
                        "SELECT count(*) FROM pg_locks WHERE NOT granted AND relation='companies'::regclass")) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void rotatingRaceLoserUsesStoredHistoricalKeyInItsFreshRecoverySession() throws Exception {
        var row = seed();
        var cmd = command(row, "rotating-race");
        var keys = Map.of("K1", Base64.getEncoder().encodeToString(new byte[32]), "K2", Base64.getEncoder()
                .encodeToString("different-rotation-key-material02".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var first = service(atomic, companies, completions, events,
                new ConfiguredUpdateFingerprints(new HmacKeys("K1", keys)));
        var second = service(atomic, companies, completions, events,
                new ConfiguredUpdateFingerprints(new HmacKeys("K2", keys)));
        try (var c = flyway.getConfiguration().getDataSource().getConnection(); var s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("LOCK TABLE companies IN EXCLUSIVE MODE");
            var a = invoke(() -> first.execute(cmd));
            var b = invoke(() -> second.execute(cmd));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> blocked() >= 2);
            c.rollback();
            assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, count("company_idempotency_records", row.company().tenantId()));
    }

    @Test
    void validatesPositiveFiniteBudget() {
        var storage = new UpdateCompany.Storage(atomic, companies, events);
        var idempotency = new UpdateCompany.Idempotency(completions, fingerprints, results);
        for (var duration : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new UpdateCompany(storage, idempotency, ids, clock, duration));
        }
    }

    @Test
    void oneBudgetDecreasesAcrossLookupWriteAndFreshRecoverySession() throws Exception {
        var row = seed();
        var cmd = command(row, "budget");
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
        assertEquals(1, await(() -> service(measured, companies, completions, events, fingerprints).execute(cmd))
                .company().version().value());
        assertEquals(3, transactions.get());
        assertTrue(checkpoints.size() > 5);
        for (int i = 1; i < checkpoints.size(); i++)
            assertTrue(checkpoints.get(i).compareTo(checkpoints.get(i - 1)) <= 0, checkpoints.toString());
        assertTrue(checkpoints.getLast().compareTo(Duration.ofSeconds(5)) < 0);
    }

    @Test
    void postgresTransientAbortsRollbackAndTheSameKeyCanRetry() throws Exception {
        for (var state : List.of("40001", "40P01")) {
            var row = seed();
            var cmd = command(row, "transient");
            var name = "hu02_abort_" + UUID.randomUUID().toString().replace("-", "");
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
                            () -> service(atomic, companies, completions, events, fingerprints).execute(cmd)));
                    assertEquals(CompanyFailure.Kind.UNAVAILABLE, ((CompanyFailure) failure.getCause()).kind());
                    assertEquals(row, await(
                            () -> atomic.read(() -> companies.find(row.company().tenantId(), row.company().id()))));
                    assertEquals(0, count("company_idempotency_records", row.company().tenantId()));
                    assertEquals(0, count("company_outbox_events", row.company().tenantId()));
                } finally {
                    st.execute("DROP TRIGGER " + name + " ON company_idempotency_records");
                    st.execute("DROP FUNCTION " + name + "()");
                }
            }
            assertEquals(1, await(() -> service(atomic, companies, completions, events, fingerprints).execute(cmd))
                    .company().version().value());
        }
    }

}
