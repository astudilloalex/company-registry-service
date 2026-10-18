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
@io.quarkus.test.junit.TestProfile(RegisterSiteTest.Profile.class)
class RegisterSiteTest {
    public static class Profile implements io.quarkus.test.junit.QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() { return Map.of("company-registry.outbox.mode", "stored-only"); }
    }
    @Inject RegisterSite registration;
    @Inject AtomicOperation atomic;
    @Inject CompanyRepository companies;
    @Inject SiteRepository sites;
    @Inject CompletionRepository completions;
    @Inject SiteFingerprints fingerprints;
    @Inject SiteResults results;
    @Inject SiteCreationEvents events;
    @Inject Identifiers ids;
    @Inject Clock clock;
    @Inject OutboxRepository outbox;

    private SiteRegistration.Command command(CompanyRecord parent, String key, String code, SiteType type) {
        return new SiteRegistration.Command(new OperationContext(parent.company().tenantId(), " Actor-aB ", UUID.randomUUID().toString()),
                key, parent.company().id(), code, " ágil ", FieldChange.present(type.name()));
    }
    private CompanyRecord parent(CompanyStatus status) {
        var now = Instant.parse("2026-10-17T10:00:00.123456Z");
        return new CompanyRecord(new Company(ids.next(), UUID.randomUUID(), UUID.randomUUID(), status, new AggregateVersion(11)),
                "PARENT", "PARENT", null, null, new Audit(now, "Creator-aB", now.plusSeconds(1), "Updater-aB"));
    }
    private RegisterSite service(AtomicOperation operation, SiteCreationEvents outbox) {
        return new RegisterSite(new RegisterSite.Storage(operation, companies, sites, outbox),
                new RegisterSite.Idempotency(completions, fingerprints, results), ids, clock, Duration.ofSeconds(10));
    }

    @Test @RunOnVertxContext void admitsEveryTypeUnderAllNonArchivedStatesAndLeavesParentUntouched(UniAsserter a) {
        for (var status : List.of(CompanyStatus.DRAFT, CompanyStatus.ACTIVE, CompanyStatus.INACTIVE)) {
            var parent = parent(status);
            a.execute(() -> atomic.execute(() -> companies.insert(parent)));
            for (var type : SiteType.values()) {
                var command = command(parent, type.name(), " " + type.name().toLowerCase(Locale.ROOT) + " ", type);
                a.assertThat(() -> registration.execute(command), result -> {
                    assertEquals(SiteStatus.DRAFT, result.site().site().status());
                    assertEquals(0, result.site().site().version().value());
                    assertEquals(type, result.site().site().type());
                    assertEquals(type.name(), result.site().code());
                    assertFalse(result.effectivelyOperational());
                    assertEquals(result.site().audit().createdAt(), result.site().audit().updatedAt());
                    assertEquals(" Actor-aB ", result.site().audit().createdBy());
                });
                a.assertEquals(() -> atomic.read(() -> companies.find(parent.company().tenantId(), parent.company().id())), parent);
            }
        }
    }

    @Test @RunOnVertxContext void rejectsArchivedMissingAndAlienParentsWithoutAcceptance(UniAsserter a) {
        var archived = parent(CompanyStatus.ARCHIVED);
        a.execute(() -> atomic.execute(() -> companies.insert(archived)));
        var command = command(archived, "archived", "x", SiteType.BRANCH);
        a.assertFailedWith(() -> registration.execute(command), failure ->
                assertEquals(SiteFailure.Kind.COMPANY_ARCHIVED, ((SiteFailure)failure).kind()));
        var missing = command(parent(CompanyStatus.DRAFT), "missing", "x", SiteType.OTHER);
        a.assertFailedWith(() -> registration.execute(missing), failure ->
                assertEquals(SiteFailure.Kind.COMPANY_NOT_FOUND, ((SiteFailure)failure).kind()));
        var alien = new SiteRegistration.Command(missing.context(), "alien", archived.company().id(), "x", "y", FieldChange.absent());
        a.assertFailedWith(() -> registration.execute(alien), failure ->
                assertEquals(SiteFailure.Kind.COMPANY_NOT_FOUND, ((SiteFailure)failure).kind()));
        a.assertNull(() -> atomic.read(() -> completions.find(new CompletionKey(archived.company().tenantId(), SiteRegistration.OPERATION, "archived"))));
    }

    @Test @RunOnVertxContext void duplicateCausesAndReplayPreserveTheOriginalAcceptance(UniAsserter a) {
        var parent = parent(CompanyStatus.DRAFT);
        a.execute(() -> atomic.execute(() -> companies.insert(parent)));
        var command = command(parent, "original", " ß ", SiteType.HEADQUARTERS);
        var original = new java.util.concurrent.atomic.AtomicReference<SiteRegistration.Result>();
        a.execute(() -> registration.execute(command).invoke(original::set));
        a.assertThat(() -> registration.execute(command), value -> assertEquals(original.get(), value));
        a.assertFailedWith(() -> registration.execute(command(parent, "duplicate-code", "SS", SiteType.BRANCH)), failure ->
                assertEquals(SiteFailure.Kind.CODE_OCCUPIED, ((SiteFailure)failure).kind()));
        a.assertFailedWith(() -> registration.execute(command(parent, "duplicate-hq", "HQ2", SiteType.HEADQUARTERS)), failure ->
                assertEquals(SiteFailure.Kind.HEADQUARTERS_OCCUPIED, ((SiteFailure)failure).kind()));
        a.assertFailedWith(() -> registration.execute(command(parent, "original", "OTHER", SiteType.HEADQUARTERS)), failure ->
                assertEquals(SiteFailure.Kind.IDEMPOTENCY_MISMATCH, ((SiteFailure)failure).kind()));
    }

    @Test @RunOnVertxContext void commitResponseLossRecoversDurableAcceptanceInANewSession(UniAsserter a) {
        var parent = parent(CompanyStatus.DRAFT);
        a.execute(() -> atomic.execute(() -> companies.insert(parent)));
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
        var command = command(parent, "lost-response", "x", SiteType.OTHER);
        var original = new java.util.concurrent.atomic.AtomicReference<SiteRegistration.Result>();
        a.execute(() -> service(lost, events).execute(command).invoke(original::set));
        a.assertThat(() -> registration.execute(command), value -> assertEquals(original.get(), value));
    }

    @Test @RunOnVertxContext void recoversEquivalentAcceptanceAfterSqlCodeCollisionUsingAFreshSession(UniAsserter a) {
        var parent = parent(CompanyStatus.DRAFT);
        a.execute(() -> atomic.execute(() -> companies.insert(parent)));
        var command = command(parent, "collision", "x", SiteType.BRANCH);
        var original = new java.util.concurrent.atomic.AtomicReference<SiteRegistration.Result>();
        a.execute(() -> registration.execute(command).invoke(original::set));
        var hidden = mock(CompletionRepository.class);
        var lookups = new java.util.concurrent.atomic.AtomicInteger();
        when(hidden.find(any())).thenAnswer(invocation ->
                lookups.incrementAndGet() <= 2 ? io.smallrye.mutiny.Uni.createFrom().nullItem() : completions.find(invocation.getArgument(0)));
        var racingSites = mock(SiteRepository.class, delegatesTo(sites));
        doReturn(io.smallrye.mutiny.Uni.createFrom().item(false)).when(racingSites)
                .codeOccupied(any(), any(), any());
        var service = new RegisterSite(new RegisterSite.Storage(atomic, companies, racingSites, events),
                new RegisterSite.Idempotency(hidden, fingerprints, results), ids, clock, Duration.ofSeconds(10));
        a.assertThat(() -> service.execute(command), value -> assertEquals(original.get(), value));
        a.execute(() -> { assertEquals(3, lookups.get()); });
    }

    @Test @RunOnVertxContext void failuresAfterEachWriteRollbackAndLeaveTheKeyReusable(UniAsserter a) {
        for (var phase : List.of("site", "completion", "event", "finalization")) {
            var parent = parent(CompanyStatus.DRAFT);
            a.execute(() -> atomic.execute(() -> companies.insert(parent)));
            var command = command(parent, phase, "x", SiteType.BRANCH);
            var written = new java.util.concurrent.atomic.AtomicReference<SiteRecord>();
            var attemptedEvent = new java.util.concurrent.atomic.AtomicReference<SiteCreated>();
            var failedSites = mock(SiteRepository.class, delegatesTo(sites));
            doAnswer(invocation -> {
                SiteRecord row = invocation.getArgument(0); written.set(row);
                return sites.insert(row).chain(value -> phase.equals("site") ? io.smallrye.mutiny.Uni.createFrom().failure(
                        new IllegalStateException("injected")) : io.smallrye.mutiny.Uni.createFrom().item(value));
            }).when(failedSites).insert(any());
            var failedCompletions = mock(CompletionRepository.class, delegatesTo(completions));
            doAnswer(invocation -> completions.insert(invocation.getArgument(0)).chain(value -> phase.equals("completion")
                    ? io.smallrye.mutiny.Uni.createFrom().failure(new IllegalStateException("injected"))
                    : io.smallrye.mutiny.Uni.createFrom().item(value))).when(failedCompletions).insert(any());
            SiteCreationEvents failedEvents = event -> { attemptedEvent.set(event); return events.store(event).chain(value -> phase.equals("event")
                    ? io.smallrye.mutiny.Uni.createFrom().failure(new IllegalStateException("injected-" + phase))
                    : io.smallrye.mutiny.Uni.createFrom().item(value)); };
            var operation = phase.equals("finalization") ? beforeCommitFailure() : atomic;
            var service = new RegisterSite(new RegisterSite.Storage(operation, companies, failedSites, failedEvents),
                    new RegisterSite.Idempotency(failedCompletions, fingerprints, results), ids, clock, Duration.ofSeconds(10));
            a.assertFailedWith(() -> service.execute(command), IllegalStateException.class);
            a.assertNull(() -> atomic.read(() -> sites.find(parent.company().tenantId(), parent.company().id(), written.get().site().id())));
            a.assertNull(() -> atomic.read(() -> completions.find(new CompletionKey(parent.company().tenantId(), SiteRegistration.OPERATION, phase))));
            a.assertNull(() -> attemptedEvent.get() == null ? io.smallrye.mutiny.Uni.createFrom().nullItem()
                    : atomic.read(() -> outbox.find(parent.company().tenantId(), attemptedEvent.get().eventId())));
            a.assertThat(() -> registration.execute(command), value -> assertEquals("X", value.site().code()));
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
