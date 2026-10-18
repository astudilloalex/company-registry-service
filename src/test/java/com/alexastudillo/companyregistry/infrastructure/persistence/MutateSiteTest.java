package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.application.site.*;
import com.alexastudillo.companyregistry.domain.model.*;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.*;
import jakarta.inject.Inject;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class)
@io.quarkus.test.junit.TestProfile(MutateSiteTest.Profile.class)
class MutateSiteTest {
    public static class Profile implements io.quarkus.test.junit.QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() { return Map.of("company-registry.outbox.mode", "stored-only"); }
    }
    @Inject MutateSite mutation;
    @Inject AtomicOperation atomic;
    @Inject CompanyRepository companies;
    @Inject SiteRepository sites;
    @Inject CompletionRepository completions;
    @Inject SiteMutationFingerprints fingerprints;
    @Inject SiteMutationResults results;
    @Inject SiteMutationEvents events;
    @Inject Identifiers ids;
    @Inject Clock clock;
    @Inject OutboxRepository outbox;

    private CompanyRecord parent() {
        var now = Instant.parse("2026-10-18T10:00:00.123456Z");
        return new CompanyRecord(new Company(ids.next(), UUID.randomUUID(), UUID.randomUUID(), CompanyStatus.DRAFT, new AggregateVersion(11)),
                "PARENT", "PARENT", null, null, new Audit(now, "Creator", now, "Creator"));
    }
    private SiteRecord site(CompanyRecord parent) {
        return new SiteRecord(new Site(ids.next(), parent.company().tenantId(), parent.company().id(), SiteType.BRANCH,
                SiteStatus.DRAFT, AggregateVersion.initial()), "A", "SEDE", parent.audit());
    }
    private SiteMutation.Command command(SiteRecord row, String key) {
        return new SiteMutation.Command(new OperationContext(row.site().tenantId(), " Actor-aB ", UUID.randomUUID().toString()),
                row.site().companyId(), row.site().id(), 0, key, SiteMutation.Operation.UPDATE,
                new SiteInformationPatch(FieldChange.present("B"), FieldChange.absent(), FieldChange.absent()));
    }
    private MutateSite service(AtomicOperation operation, SiteMutationEvents outbox) {
        return new MutateSite(new MutateSite.Storage(operation, companies, sites, outbox),
                new MutateSite.Idempotency(completions, fingerprints, results), ids, clock, Duration.ofSeconds(10));
    }
    @Test @RunOnVertxContext void failuresAfterEachWriteRollbackAndLeaveTheKeyReusable(UniAsserter a) {
        for (var phase : List.of("site", "completion", "event", "finalization")) {
            var parent = parent();
            var row = site(parent);
            a.execute(() -> atomic.execute(() -> companies.insert(parent).chain(() -> sites.insert(row))));
            var command = command(row, phase);
            var written = new java.util.concurrent.atomic.AtomicReference<SiteRecord>();
            var attemptedEvent = new java.util.concurrent.atomic.AtomicReference<SiteMutated>();
            var failedSites = mock(SiteRepository.class, delegatesTo(sites));
            doAnswer(invocation -> {
                SiteRecord updated = invocation.getArgument(0); written.set(updated);
                return sites.update(updated, invocation.getArgument(1)).chain(value -> phase.equals("site") ? io.smallrye.mutiny.Uni.createFrom().failure(
                        new IllegalStateException("injected")) : io.smallrye.mutiny.Uni.createFrom().item(value));
            }).when(failedSites).update(any(), anyLong());
            var failedCompletions = mock(CompletionRepository.class, delegatesTo(completions));
            doAnswer(invocation -> completions.insert(invocation.getArgument(0)).chain(value -> phase.equals("completion")
                    ? io.smallrye.mutiny.Uni.createFrom().failure(new IllegalStateException("injected"))
                    : io.smallrye.mutiny.Uni.createFrom().item(value))).when(failedCompletions).insert(any());
            SiteMutationEvents failedEvents = event -> { attemptedEvent.set(event); return events.store(event).chain(value -> phase.equals("event")
                    ? io.smallrye.mutiny.Uni.createFrom().failure(new IllegalStateException("injected-" + phase))
                    : io.smallrye.mutiny.Uni.createFrom().item(value)); };
            var operation = phase.equals("finalization") ? beforeCommitFailure() : atomic;
            var service = new MutateSite(new MutateSite.Storage(operation, companies, failedSites, failedEvents),
                    new MutateSite.Idempotency(failedCompletions, fingerprints, results), ids, clock, Duration.ofSeconds(10));
            a.assertFailedWith(() -> service.execute(command), IllegalStateException.class);
            a.assertEquals(() -> atomic.read(() -> sites.find(row.site().tenantId(), row.site().companyId(), row.site().id())), row);
            a.assertNull(() -> atomic.read(() -> completions.find(new CompletionKey(parent.company().tenantId(), SiteMutation.Operation.UPDATE.namespace(), phase))));
            a.assertNull(() -> attemptedEvent.get() == null ? io.smallrye.mutiny.Uni.createFrom().nullItem()
                    : atomic.read(() -> outbox.find(parent.company().tenantId(), attemptedEvent.get().eventId())));
            a.assertThat(() -> mutation.execute(command), value -> assertEquals("B", value.site().code()));
        }
    }
    @Test @RunOnVertxContext void commitResponseLossRecoversDurableAcceptanceInANewSession(UniAsserter a) {
        var parent = parent();
        var row = site(parent);
        a.execute(() -> atomic.execute(() -> companies.insert(parent).chain(() -> sites.insert(row))));
        var lost = new AtomicOperation() {
            @Override public <T> io.smallrye.mutiny.Uni<T> execute(java.util.function.Supplier<io.smallrye.mutiny.Uni<T>> work) { return atomic.execute(work); }
            @Override public <T> io.smallrye.mutiny.Uni<T> read(java.util.function.Supplier<io.smallrye.mutiny.Uni<T>> work) { return atomic.read(work); }
            @Override public <T> io.smallrye.mutiny.Uni<T> read(Duration timeout, java.util.function.Supplier<io.smallrye.mutiny.Uni<T>> work) { return atomic.read(timeout, work); }
            @Override public io.smallrye.mutiny.Uni<Void> checkpoint(Duration remaining) { return atomic.checkpoint(remaining); }
            @Override public <T> io.smallrye.mutiny.Uni<T> execute(Duration timeout, java.util.function.Supplier<io.smallrye.mutiny.Uni<T>> work) {
                return atomic.execute(timeout, work).chain(_ -> io.smallrye.mutiny.Uni.createFrom().failure(
                        new PersistenceFailure(PersistenceFailure.Kind.UNAVAILABLE, null)));
            }
        };
        var command = command(row, "lost-response");
        var original = new java.util.concurrent.atomic.AtomicReference<SiteMutation.Result>();
        a.execute(() -> service(lost, events).execute(command).invoke(original::set));
        a.assertThat(() -> mutation.execute(command), value -> assertEquals(original.get(), value));
    }

    @Test @RunOnVertxContext void unkeyedCommitResponseLossReportsUncertaintyWithoutRepeatingTheWrite(UniAsserter a) {
        var parent = parent();
        var row = site(parent);
        a.execute(() -> atomic.execute(() -> companies.insert(parent).chain(() -> sites.insert(row))));
        var lost = new AtomicOperation() {
            @Override public <T> io.smallrye.mutiny.Uni<T> execute(java.util.function.Supplier<io.smallrye.mutiny.Uni<T>> work) { return atomic.execute(work); }
            @Override public <T> io.smallrye.mutiny.Uni<T> read(java.util.function.Supplier<io.smallrye.mutiny.Uni<T>> work) { return atomic.read(work); }
            @Override public <T> io.smallrye.mutiny.Uni<T> read(Duration timeout, java.util.function.Supplier<io.smallrye.mutiny.Uni<T>> work) { return atomic.read(timeout, work); }
            @Override public io.smallrye.mutiny.Uni<Void> checkpoint(Duration remaining) { return atomic.checkpoint(remaining); }
            @Override public <T> io.smallrye.mutiny.Uni<T> execute(Duration timeout, java.util.function.Supplier<io.smallrye.mutiny.Uni<T>> work) {
                return atomic.execute(timeout, work).chain(_ -> io.smallrye.mutiny.Uni.createFrom().failure(
                        new PersistenceFailure(PersistenceFailure.Kind.UNAVAILABLE, null)));
            }
        };
        var command = command(row, null);
        a.assertFailedWith(() -> service(lost, events).execute(command), failure ->
                assertEquals(SiteMutationFailure.Kind.UNAVAILABLE, ((SiteMutationFailure) failure).kind()));
        a.assertThat(() -> atomic.read(() -> sites.find(row.site().tenantId(), row.site().companyId(), row.site().id())), value -> {
            assertEquals(1, value.site().version().value());
            assertEquals("B", value.code());
        });
    }

    @Test @RunOnVertxContext void noOpWithAnEarlierAcceptanceClockReplaysItsUnchangedSite(UniAsserter a) {
        var parent = parent();
        var original = site(parent);
        var audit = original.audit();
        var row = new SiteRecord(original.site(), original.code(), original.name(),
                new Audit(audit.createdAt().minusSeconds(60), audit.createdBy(), audit.updatedAt(), audit.updatedBy()));
        var acceptedAt = audit.updatedAt().minusMillis(1);
        var context = new OperationContext(row.site().tenantId(), "NoOpActor", UUID.randomUUID().toString());
        var command = new SiteMutation.Command(context, row.site().companyId(), row.site().id(), 0,
                "clock-skew", SiteMutation.Operation.UPDATE, SiteInformationPatch.empty());
        var quietEvents = mock(SiteMutationEvents.class);
        var service = new MutateSite(new MutateSite.Storage(atomic, companies, sites, quietEvents),
                new MutateSite.Idempotency(completions, fingerprints, results), ids,
                Clock.fixed(acceptedAt, ZoneOffset.UTC), Duration.ofSeconds(10));
        a.execute(() -> atomic.execute(() -> companies.insert(parent).chain(() -> sites.insert(row))));
        a.assertThat(() -> service.execute(command), value -> assertEquals(row, value.site()));
        a.assertThat(() -> atomic.read(() -> completions.find(new CompletionKey(context.tenantId(),
                command.operation().namespace(), command.idempotencyKey()))), stored -> {
            assertEquals(acceptedAt, stored.createdAt());
            assertEquals(context.actor(), stored.createdBy());
        });
        a.assertThat(() -> mutation.execute(command), value -> assertEquals(row, value.site()));
        a.assertThat(() -> atomic.read(() -> sites.find(context.tenantId(), command.companyId(), command.siteId())),
                value -> assertEquals(row, value));
        a.execute(() -> {
            verify(quietEvents, never()).store(any());
        });
    }

    @Test @RunOnVertxContext void modesStoreOnlyTheSelectedFactAndNoOpReplayStoreNone(UniAsserter a) {
        for (var mode : List.of("disabled", "stored-only", "published")) {
            var config = new io.smallrye.config.SmallRyeConfigBuilder()
                    .withConverter(Duration.class, 100, new io.quarkus.runtime.configuration.DurationConverter())
                    .withMapping(com.alexastudillo.companyregistry.infrastructure.outbox.OutboxConfiguration.class)
                    .withDefaultValue("company-registry.outbox.mode", mode).build()
                    .getConfigMapping(com.alexastudillo.companyregistry.infrastructure.outbox.OutboxConfiguration.class);
            var policy = new com.alexastudillo.companyregistry.infrastructure.outbox.OutboxPolicy(config, outbox);
            var adapter = new com.alexastudillo.companyregistry.infrastructure.outbox.SiteMutatedOutbox(policy);
            var count = new java.util.concurrent.atomic.AtomicInteger();
            var facts = new ArrayList<SiteMutated>();
            SiteMutationEvents counted = event -> { count.incrementAndGet(); facts.add(event); return adapter.store(event); };
            var parent = parent();
            var row = site(parent);
            var command = command(row, "mode");
            a.execute(() -> atomic.execute(() -> companies.insert(parent).chain(() -> sites.insert(row))));
            var service = service(atomic, counted);
            a.assertThat(() -> service.execute(command), value -> assertEquals(1, value.site().site().version().value()));
            a.assertThat(() -> service.execute(command), value -> assertEquals(1, value.site().site().version().value()));
            var noop = new SiteMutation.Command(command.context(), command.companyId(), command.siteId(), 1,
                    "noop", SiteMutation.Operation.UPDATE, SiteInformationPatch.empty());
            a.assertThat(() -> service.execute(noop), value -> assertEquals(1, value.site().site().version().value()));
            a.execute(() -> { assertEquals(1, count.get()); });
            var close = new SiteMutation.Command(command.context(), command.companyId(), command.siteId(), 1,
                    "close", SiteMutation.Operation.CLOSE, SiteInformationPatch.empty());
            a.assertThat(() -> service.execute(close), value -> assertEquals(SiteStatus.CLOSED, value.site().site().status()));
            a.execute(() -> { assertEquals(2, count.get()); });
            for (int index : new int[] {0, 1}) {
                int position = index;
                a.assertThat(() -> atomic.read(() -> outbox.find(parent.company().tenantId(), facts.get(position).eventId())), stored -> {
                    if (mode.equals("disabled")) assertNull(stored);
                    else {
                        assertEquals(position == 0 ? "company.site.updated.v1" : "company.site.closed.v1", stored.eventType());
                        assertEquals(OutboxStatus.PENDING, stored.status());
                        assertEquals(0, stored.attempts());
                        assertEquals(0, stored.version());
                    }
                });
            }
        }
    }

    private AtomicOperation beforeCommitFailure() {
        return new AtomicOperation() {
            @Override public <T> io.smallrye.mutiny.Uni<T> execute(java.util.function.Supplier<io.smallrye.mutiny.Uni<T>> work) { return atomic.execute(work); }
            @Override public <T> io.smallrye.mutiny.Uni<T> read(java.util.function.Supplier<io.smallrye.mutiny.Uni<T>> work) { return atomic.read(work); }
            @Override public <T> io.smallrye.mutiny.Uni<T> read(Duration timeout, java.util.function.Supplier<io.smallrye.mutiny.Uni<T>> work) { return atomic.read(timeout, work); }
            @Override public io.smallrye.mutiny.Uni<Void> checkpoint(Duration remaining) { return atomic.checkpoint(remaining); }
            @Override public <T> io.smallrye.mutiny.Uni<T> execute(Duration timeout, java.util.function.Supplier<io.smallrye.mutiny.Uni<T>> work) {
                return atomic.execute(timeout, () -> work.get().chain(_ -> io.smallrye.mutiny.Uni.createFrom().failure(new IllegalStateException("before-commit"))));
            }
        };
    }

}
