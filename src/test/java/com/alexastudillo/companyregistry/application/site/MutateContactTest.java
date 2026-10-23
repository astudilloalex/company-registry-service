package com.alexastudillo.companyregistry.application.site;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.domain.model.*;
import io.smallrye.mutiny.Uni;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class MutateContactTest {
    private static final Instant NOW = Instant.parse("2026-10-18T20:00:00.123456Z");
    private final ContactScope scope = new ContactScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final OperationContext context = new OperationContext(scope.tenantId(), "Actor-aB",
            UUID.randomUUID().toString());
    private final UUID contactId = UUID.fromString("019a2f00-0000-7000-8000-000000000003");
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final SiteRepository sites = mock(SiteRepository.class);
    private final CompletionRepository completions = mock(CompletionRepository.class);
    private final SiteMutationEvents events = mock(SiteMutationEvents.class);
    private final ContactFingerprints fingerprints = mock(ContactFingerprints.class);
    private final ContactResults results = mock(ContactResults.class);
    private final MemoryAtomic atomic = new MemoryAtomic();

    private static final class MemoryAtomic implements AtomicOperation {
        int open;
        int writes;
        int reads;

        @Override
        public <T> Uni<T> execute(Supplier<Uni<T>> work) {
            writes++;
            return session(work);
        }

        @Override
        public <T> Uni<T> read(Supplier<Uni<T>> work) {
            reads++;
            return session(work);
        }

        private <T> Uni<T> session(Supplier<Uni<T>> work) {
            return Uni.createFrom().deferred(() -> {
                open++;
                return work.get();
            }).eventually(() -> open--);
        }
    }

    private MutateContact service() {
        when(completions.find(any())).thenReturn(Uni.createFrom().nullItem());
        when(completions.insert(any()))
                .thenAnswer(call -> Uni.createFrom().item(call.getArgument(0, CompletionRecord.class)));
        when(fingerprints.sign(any())).thenReturn(new ContactFingerprints.Signature((short) 1, "K1", "a".repeat(64)));
        when(results.currentVersion()).thenReturn((short) 1);
        when(results.encode(any(), any())).thenReturn(Map.of("original", true));
        when(events.store(any())).thenReturn(Uni.createFrom().nullItem());
        when(sites.update(any(), anyLong()))
                .thenAnswer(call -> Uni.createFrom().item(call.getArgument(0, SiteRecord.class)));
        when(sites.updateContact(any(), any()))
                .thenAnswer(call -> Uni.createFrom().item(call.getArgument(1, ContactRecord.class)));
        when(sites.insertContact(any(), any(), any()))
                .thenAnswer(call -> Uni.createFrom().item(call.getArgument(2, ContactRecord.class)));
        when(sites.primaryContact(any(), any())).thenReturn(Uni.createFrom().nullItem());
        when(sites.contactValueOccupiedByOther(any(), any(), anyString(), any()))
                .thenReturn(Uni.createFrom().item(false));
        state(CompanyStatus.DRAFT, SiteStatus.DRAFT, 0);
        when(sites.contactDetail(scope, contactId))
                .thenReturn(Uni.createFrom().item(new ContactMutation.View(scope, contact(), 0)));
        var ids = new AtomicInteger(10);
        return new MutateContact(new MutateContact.Storage(atomic, companies, sites, events),
                new MutateContact.Idempotency(completions, fingerprints, results),
                () -> new UUID(0x019a2f0000007000L, 0x8000000000000000L + ids.incrementAndGet()),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(5));
    }

    @Test
    void noOpPreservesAuditAndVersionAndOnlyStoresOptionalAcceptance() {
        var service = service();
        var command = command(ContactMutation.Operation.UPDATE, ContactInformationPatch.empty(), "noop");
        var result = service.execute(command).await().atMost(Duration.ofSeconds(2));
        assertEquals(new ContactMutation.View(scope, contact(), 0), result);
        verify(completions).insert(any());
        verify(sites, never()).update(any(), anyLong());
        verify(sites, never()).updateContact(any(), any());
        verifyNoInteractions(events);
        assertEquals(0, atomic.open);
    }

    @Test
    void deletionIsLocalAndReturnsReceiptBeforeAnyGeographicDependency() {
        var service = service();
        var result = service.execute(command(ContactMutation.Operation.DELETE, ContactInformationPatch.empty(), null))
                .await().atMost(Duration.ofSeconds(2));
        assertEquals(new ContactMutation.Deletion(scope, contactId, 1, NOW, context.actor()), result);
        verifyNoInteractions(completions);
        verify(events).store(any());
        assertEquals(1, atomic.writes);
    }

    @Test
    void removedContactPrecedesStaleVersionAndTerminalState() {
        var service = service();
        state(CompanyStatus.ARCHIVED, SiteStatus.CLOSED, 5);
        when(sites.contactDetail(scope, contactId)).thenReturn(Uni.createFrom().nullItem());
        var call = service.execute(command(ContactMutation.Operation.DELETE, ContactInformationPatch.empty(), null))
                .await();
        var timeout = Duration.ofSeconds(2);
        assertEquals(ContactFailure.Kind.CONTACT_NOT_FOUND,
                assertThrows(ContactFailure.class, () -> call.atMost(timeout)).kind());
        verifyNoInteractions(events);
    }

    @Test
    void everyEditableStateAllowsNoOpButVersionAndParentTerminalityHavePriority() {
        var service = service();
        var command = command(ContactMutation.Operation.UPDATE, ContactInformationPatch.empty(), null);
        for (var parent : new CompanyStatus[] { CompanyStatus.DRAFT, CompanyStatus.ACTIVE, CompanyStatus.INACTIVE }) {
            for (var site : new SiteStatus[] { SiteStatus.DRAFT, SiteStatus.ACTIVE, SiteStatus.INACTIVE }) {
                state(parent, site, 0);
                assertEquals(0, service.execute(command).await().atMost(Duration.ofSeconds(2)).siteVersion());
            }
        }
        state(CompanyStatus.ARCHIVED, SiteStatus.CLOSED, 1);
        assertFailure(service, command, ContactFailure.Kind.STALE_VERSION);
        state(CompanyStatus.ARCHIVED, SiteStatus.CLOSED, 0);
        assertFailure(service, command, ContactFailure.Kind.COMPANY_ARCHIVED);
        state(CompanyStatus.DRAFT, SiteStatus.CLOSED, 0);
        assertFailure(service, command, ContactFailure.Kind.SITE_CLOSED);
        verifyNoInteractions(events);
    }

    @Test
    void uncertainFinalizationDoesNotStartRecoveryOrReexecute() {
        service();
        var uncertain = new AtomicOperation() {
            @Override
            public <T> Uni<T> read(Supplier<Uni<T>> work) {
                atomic.reads++;
                return work.get();
            }

            @Override
            public <T> Uni<T> execute(Supplier<Uni<T>> work) {
                atomic.writes++;
                return Uni.createFrom().failure(PersistenceFailure.finalizationUncertain());
            }
        };
        var service = new MutateContact(new MutateContact.Storage(uncertain, companies, sites, events),
                new MutateContact.Idempotency(completions, fingerprints, results),
                null, Clock.systemUTC(), Duration.ofSeconds(1));
        assertFailure(service, command(ContactMutation.Operation.UPDATE, ContactInformationPatch.empty(), "key"),
                ContactFailure.Kind.UNAVAILABLE);
        assertEquals(1, atomic.reads);
        assertEquals(1, atomic.writes);
    }

    @Test
    void originalReplayAvoidsResourceAndGeographicReads() {
        var service = service();
        var command = command(ContactMutation.Operation.UPDATE, ContactInformationPatch.empty(), "key");
        var key = new CompletionKey(scope.tenantId(), command.intent().operation().namespace(), "key");
        var stored = new CompletionRecord(key, "a".repeat(64), scope.companyId(), scope.siteId(), 0L, (short) 200,
                (short) 1,
                Map.of("original", true), NOW, context.actor(), (short) 1, "K1");
        var original = new ContactMutation.View(scope, contact(), 0);
        when(completions.find(key)).thenReturn(Uni.createFrom().item(stored));
        when(fingerprints.matches(command, (short) 1, "K1", stored.requestHash())).thenReturn(true);
        when(results.decode(stored, contactId)).thenReturn(original);
        assertEquals(original, service.execute(command).await().atMost(Duration.ofSeconds(2)));
        verifyNoInteractions(companies, sites, events);
        assertEquals(0, atomic.writes);
    }

    @Test
    void acceptanceFoundAfterCompanyLockUsesOriginalRawIntentionForHistoricalPolicy() {
        var service = service();
        var empty = ContactInformationPatch.empty();
        var patch = new ContactInformationPatch(empty.contactType(), empty.value(), FieldChange.present(" ventas "),
                empty.isPrimary());
        var command = command(ContactMutation.Operation.UPDATE, patch, "late");
        var key = new CompletionKey(scope.tenantId(), command.intent().operation().namespace(), "late");
        var stored = new CompletionRecord(key, "a".repeat(64), scope.companyId(), scope.siteId(), 0L, (short) 200,
                (short) 1,
                Map.of("original", true), NOW, context.actor(), (short) 1, "K1");
        var original = new ContactMutation.View(scope, contact(), 0);
        when(completions.find(key)).thenReturn(Uni.createFrom().nullItem())
                .thenReturn(Uni.createFrom().item(stored));
        when(fingerprints.matches(command, (short) 1, "K1", stored.requestHash())).thenReturn(true);
        when(results.decode(stored, contactId)).thenReturn(original);
        assertEquals(original, service.execute(command).await().atMost(Duration.ofSeconds(2)));
        verify(fingerprints).matches(command, (short) 1, "K1", stored.requestHash());
        verifyNoInteractions(events);
        verify(sites, never()).findForUpdate(any(), any(), any());
        verify(completions, never()).insert(any());
    }

    @Test
    void deadlineRejectsNonpositiveNullAndOverflowBudgets() {
        for (var invalid : new Duration[] { null, Duration.ZERO, Duration.ofSeconds(-1),
                Duration.ofSeconds(Long.MAX_VALUE) }) {
            assertThrows(IllegalArgumentException.class, () -> ContactDeadline.validate(invalid));
        }
        assertEquals(10_000_000_000L, ContactDeadline.validate(Duration.ofSeconds(10)));
        var deadline = new ContactDeadline(Duration.ofSeconds(10));
        assertTrue(deadline.remaining().compareTo(Duration.ZERO) > 0);
        assertTrue(deadline.remaining().compareTo(Duration.ofSeconds(10)) < 0);
    }

    @Test
    void lostAcceptedResponseRecoversOnlyAfterCloseWithDecreasingBudgetAndNoSecondWrite() {
        service();
        var stored = new java.util.concurrent.atomic.AtomicReference<CompletionRecord>();
        var accepted = new java.util.concurrent.atomic.AtomicReference<ContactMutation.Result>();
        when(completions.find(any())).thenAnswer(_ -> Uni.createFrom().item(stored.get()));
        when(completions.insert(any())).thenAnswer(call -> {
            var completion = call.getArgument(0, CompletionRecord.class);
            stored.set(completion);
            return Uni.createFrom().item(completion);
        });
        when(results.encode(any(), any())).thenAnswer(call -> {
            accepted.set(call.getArgument(1));
            return Map.of("original", true);
        });
        when(results.decode(any(), any())).thenAnswer(_ -> accepted.get());
        when(fingerprints.matches(any(), anyShort(), anyString(), anyString())).thenReturn(true);
        var checkpoints = new ArrayList<Duration>();
        var measured = new AtomicOperation() {
            @Override
            public <T> Uni<T> read(Supplier<Uni<T>> work) {
                assertEquals(0, atomic.open);
                return atomic.read(work);
            }

            @Override
            public <T> Uni<T> execute(Supplier<Uni<T>> work) {
                return atomic.execute(work).chain(_ -> Uni.createFrom()
                        .failure(new PersistenceFailure(PersistenceFailure.Kind.UNAVAILABLE, null)));
            }

            @Override
            public <T> Uni<T> read(Duration remaining, Supplier<Uni<T>> work) {
                checkpoints.add(remaining);
                return read(work);
            }

            @Override
            public <T> Uni<T> execute(Duration remaining, Supplier<Uni<T>> work) {
                checkpoints.add(remaining);
                return execute(work);
            }
        };
        var service = new MutateContact(new MutateContact.Storage(measured, companies, sites, events),
                new MutateContact.Idempotency(completions, fingerprints, results),
                () -> UUID.fromString("019a2f00-0000-7000-8000-000000000010"), Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(5));
        var empty = ContactInformationPatch.empty();
        var patch = new ContactInformationPatch(empty.contactType(), empty.value(), FieldChange.present("NEW"),
                empty.isPrimary());
        assertEquals(1, service.execute(command(ContactMutation.Operation.UPDATE, patch, "lost")).await()
                .atMost(Duration.ofSeconds(2)).siteVersion());
        assertEquals(1, atomic.writes);
        assertEquals(2, atomic.reads);
        assertEquals(0, atomic.open);
        assertEquals(3, checkpoints.size());
        assertTrue(checkpoints.get(1).compareTo(checkpoints.getFirst()) <= 0);
        assertTrue(checkpoints.getLast().compareTo(checkpoints.get(1)) <= 0);
        verify(events, times(1)).store(any());
    }

    private void state(CompanyStatus companyStatus, SiteStatus siteStatus, long version) {
        var audit = new Audit(NOW.minusSeconds(1), "Creator", NOW.minusSeconds(1), "Creator");
        when(companies.findForUpdate(scope.tenantId(), scope.companyId())).thenReturn(Uni.createFrom()
                .item(new CompanyRecord(
                        new Company(scope.companyId(), scope.tenantId(), UUID.randomUUID(), companyStatus,
                                AggregateVersion.initial()),
                        "CODE", "NAME", null, null, audit)));
        when(sites.findForUpdate(scope.tenantId(), scope.companyId(), scope.siteId())).thenReturn(Uni.createFrom()
                .item(new SiteRecord(
                        new Site(scope.siteId(), scope.tenantId(), scope.companyId(), SiteType.BRANCH, siteStatus,
                                new AggregateVersion(version)),
                        "SITE", "NAME", audit)));
    }

    private ContactRecord contact() {
        return new ContactRecord(contactId, scope.siteId(), ContactType.EMAIL, "a@example.com", null,
                false, new Audit(NOW.minusSeconds(1), "Creator", NOW.minusSeconds(1), "Creator"), null, null);
    }

    private ContactMutation.Command command(ContactMutation.Operation operation, ContactInformationPatch patch,
            String key) {
        return new ContactMutation.Command(context, scope, 0, key, new ContactMutation.Intent(operation,
                operation == ContactMutation.Operation.CREATE ? null : contactId, patch));
    }

    private static void assertFailure(MutateContact service, ContactMutation.Command command,
            ContactFailure.Kind kind) {
        var awaitable = service.execute(command).await();
        var timeout = Duration.ofSeconds(2);
        assertEquals(kind, assertThrows(ContactFailure.class, () -> awaitable.atMost(timeout)).kind());
    }
}
