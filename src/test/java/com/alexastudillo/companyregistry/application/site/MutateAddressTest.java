package com.alexastudillo.companyregistry.application.site;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.application.reference.*;
import com.alexastudillo.companyregistry.application.reference.References.*;
import com.alexastudillo.companyregistry.domain.model.*;
import io.smallrye.mutiny.Uni;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class MutateAddressTest {
    private static final Instant NOW = Instant.parse("2026-10-18T20:00:00.123456Z");
    private final AddressScope scope = new AddressScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final OperationContext context = new OperationContext(scope.tenantId(), "Actor-aB", UUID.randomUUID().toString());
    private final UUID addressId = UUID.fromString("019a2f00-0000-7000-8000-000000000003");
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final SiteRepository sites = mock(SiteRepository.class);
    private final CompletionRepository completions = mock(CompletionRepository.class);
    private final SiteMutationEvents events = mock(SiteMutationEvents.class);
    private final AddressFingerprints fingerprints = mock(AddressFingerprints.class);
    private final AddressResults results = mock(AddressResults.class);
    private final CountryReferences countries = mock(CountryReferences.class);
    private final DivisionReferences divisions = mock(DivisionReferences.class);
    private final MemoryAtomic atomic = new MemoryAtomic();

    private static final class MemoryAtomic implements AtomicOperation {
        int open;
        int writes;
        int reads;
        @Override public <T> Uni<T> execute(Supplier<Uni<T>> work) { writes++; return session(work); }
        @Override public <T> Uni<T> read(Supplier<Uni<T>> work) { reads++; return session(work); }
        private <T> Uni<T> session(Supplier<Uni<T>> work) {
            return Uni.createFrom().deferred(() -> { open++; return work.get(); }).eventually(() -> open--);
        }
    }

    private MutateAddress service() {
        when(completions.find(any())).thenReturn(Uni.createFrom().nullItem());
        when(completions.insert(any())).thenAnswer(call -> Uni.createFrom().item(call.getArgument(0, CompletionRecord.class)));
        when(fingerprints.sign(any())).thenReturn(new AddressFingerprints.Signature((short) 1, "K1", "a".repeat(64)));
        when(results.currentVersion()).thenReturn((short) 1);
        when(results.encode(any(), any())).thenReturn(Map.of("original", true));
        when(events.store(any())).thenReturn(Uni.createFrom().nullItem());
        when(sites.update(any(), anyLong())).thenAnswer(call -> Uni.createFrom().item(call.getArgument(0, SiteRecord.class)));
        when(sites.updateAddress(any(), any())).thenAnswer(call -> Uni.createFrom().item(call.getArgument(1, AddressRecord.class)));
        when(sites.insertAddress(any(), any(), any())).thenAnswer(call -> Uni.createFrom().item(call.getArgument(2, AddressRecord.class)));
        when(sites.primaryAddress(any(), any())).thenReturn(Uni.createFrom().nullItem());
        state(CompanyStatus.DRAFT, SiteStatus.DRAFT, 0);
        when(sites.addressDetail(scope, addressId)).thenReturn(Uni.createFrom().item(new AddressMutation.View(scope, address(), 0)));
        var ids = new AtomicInteger(10);
        return new MutateAddress(new MutateAddress.Storage(atomic, companies, sites, events),
                new MutateAddress.Idempotency(completions, fingerprints, results), new AddressGeography(countries, divisions),
                () -> new UUID(0x019a2f0000007000L, 0x8000000000000000L + ids.incrementAndGet()),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(5));
    }

    @Test
    void noOpPreservesAuditAndVersionAndOnlyStoresOptionalAcceptance() {
        var service = service();
        var command = command(AddressMutation.Operation.UPDATE, AddressInformationPatch.empty(), "noop");
        var result = service.execute(command).await().atMost(Duration.ofSeconds(2));
        assertEquals(new AddressMutation.View(scope, address(), 0), result);
        verify(completions).insert(any());
        verify(sites, never()).update(any(), anyLong());
        verify(sites, never()).updateAddress(any(), any());
        verifyNoInteractions(events, countries, divisions);
        assertEquals(0, atomic.open);
    }

    @Test
    void creationQueriesGeographyOnlyBetweenFinalizedSessionsAndAcceptsOnce() {
        var service = service();
        when(countries.find(context, "EC")).thenAnswer(_ -> {
            assertEquals(0, atomic.open);
            return Uni.createFrom().item(new CountryReference(UUID.randomUUID(), "EC", GeographicState.ACTIVE));
        });
        var empty = AddressInformationPatch.empty();
        var patch = new AddressInformationPatch(FieldChange.present("physical"), new AddressInformationPatch.TextChanges(
                FieldChange.present("ec"), FieldChange.present("avenida"), FieldChange.absent(), FieldChange.absent(),
                FieldChange.absent(), FieldChange.absent()), empty.isPrimary(), empty.coordinates());
        var result = service.execute(command(AddressMutation.Operation.CREATE, patch, "create")).await().atMost(Duration.ofSeconds(2));
        assertEquals(1, result.siteVersion());
        assertEquals(2, atomic.writes);
        assertEquals(0, atomic.open);
        verify(sites, times(1)).insertAddress(any(), any(), any());
        verify(sites, times(1)).update(any(), anyLong());
        verify(events, times(1)).store(any());
    }

    @Test
    void deletionIsLocalAndReturnsReceiptBeforeAnyGeographicDependency() {
        var service = service();
        var result = service.execute(command(AddressMutation.Operation.DELETE, AddressInformationPatch.empty(), null))
                .await().atMost(Duration.ofSeconds(2));
        assertEquals(new AddressMutation.Deletion(scope, addressId, 1, NOW, context.actor()), result);
        verifyNoInteractions(countries, divisions, completions);
        verify(events).store(any());
        assertEquals(1, atomic.writes);
    }

    @Test
    void removedAddressPrecedesStaleVersionAndTerminalState() {
        var service = service();
        state(CompanyStatus.ARCHIVED, SiteStatus.CLOSED, 5);
        when(sites.addressDetail(scope, addressId)).thenReturn(Uni.createFrom().nullItem());
        var call = service.execute(command(AddressMutation.Operation.DELETE, AddressInformationPatch.empty(), null)).await();
        var timeout = Duration.ofSeconds(2);
        assertEquals(AddressFailure.Kind.ADDRESS_NOT_FOUND, assertThrows(AddressFailure.class, () -> call.atMost(timeout)).kind());
        verifyNoInteractions(events, countries, divisions);
    }

    @Test
    void everyEditableStateAllowsNoOpButVersionAndParentTerminalityHavePriority() {
        var service = service();
        var command = command(AddressMutation.Operation.UPDATE, AddressInformationPatch.empty(), null);
        for (var parent : new CompanyStatus[] {CompanyStatus.DRAFT, CompanyStatus.ACTIVE, CompanyStatus.INACTIVE}) {
            for (var site : new SiteStatus[] {SiteStatus.DRAFT, SiteStatus.ACTIVE, SiteStatus.INACTIVE}) {
                state(parent, site, 0);
                assertEquals(0, service.execute(command).await().atMost(Duration.ofSeconds(2)).siteVersion());
            }
        }
        state(CompanyStatus.ARCHIVED, SiteStatus.CLOSED, 1);
        assertFailure(service, command, AddressFailure.Kind.STALE_VERSION);
        state(CompanyStatus.ARCHIVED, SiteStatus.CLOSED, 0);
        assertFailure(service, command, AddressFailure.Kind.COMPANY_ARCHIVED);
        state(CompanyStatus.DRAFT, SiteStatus.CLOSED, 0);
        assertFailure(service, command, AddressFailure.Kind.SITE_CLOSED);
        verifyNoInteractions(events, countries, divisions);
    }

    @Test
    void currentAbsenceAndRevisionPrecedeNegativeRemoteObservation() {
        var empty = AddressInformationPatch.empty();
        var patch = new AddressInformationPatch(empty.addressType(), new AddressInformationPatch.TextChanges(FieldChange.present("CO"),
                FieldChange.absent(), FieldChange.absent(), FieldChange.absent(), FieldChange.absent(), FieldChange.absent()),
                empty.isPrimary(), empty.coordinates());
        for (boolean deleted : new boolean[] {false, true}) {
            var service = service();
            doAnswer(_ -> {
                assertEquals(0, atomic.open);
                state(CompanyStatus.DRAFT, SiteStatus.DRAFT, 1);
                if (deleted) when(sites.addressDetail(scope, addressId)).thenReturn(Uni.createFrom().nullItem());
                return Uni.createFrom().failure(new ReferenceFailure(ReferenceFailure.Kind.UNAVAILABLE));
            }).when(countries).find(context, "CO");
            assertFailure(service, command(AddressMutation.Operation.UPDATE, patch, null),
                    deleted ? AddressFailure.Kind.ADDRESS_NOT_FOUND : AddressFailure.Kind.STALE_VERSION);
        }
        verifyNoInteractions(events);
    }

    @Test
    void uncertainFinalizationDoesNotStartRecoveryOrReexecute() {
        service();
        var uncertain = new AtomicOperation() {
            @Override public <T> Uni<T> read(Supplier<Uni<T>> work) { atomic.reads++; return work.get(); }
            @Override public <T> Uni<T> execute(Supplier<Uni<T>> work) { atomic.writes++; return Uni.createFrom().failure(PersistenceFailure.finalizationUncertain()); }
        };
        var service = new MutateAddress(new MutateAddress.Storage(uncertain, companies, sites, events),
                new MutateAddress.Idempotency(completions, fingerprints, results), new AddressGeography(countries, divisions),
                null, Clock.systemUTC(), Duration.ofSeconds(1));
        assertFailure(service, command(AddressMutation.Operation.UPDATE, AddressInformationPatch.empty(), "key"), AddressFailure.Kind.UNAVAILABLE);
        assertEquals(1, atomic.reads);
        assertEquals(1, atomic.writes);
    }

    @Test
    void originalReplayAvoidsResourceAndGeographicReads() {
        var service = service();
        var command = command(AddressMutation.Operation.UPDATE, AddressInformationPatch.empty(), "key");
        var key = new CompletionKey(scope.tenantId(), command.intent().operation().namespace(), "key");
        var stored = new CompletionRecord(key, "a".repeat(64), scope.companyId(), scope.siteId(), 0L, (short) 200, (short) 1,
                Map.of("original", true), NOW, context.actor(), (short) 1, "K1");
        var original = new AddressMutation.View(scope, address(), 0);
        when(completions.find(key)).thenReturn(Uni.createFrom().item(stored));
        when(fingerprints.matches(command, (short) 1, "K1", stored.requestHash())).thenReturn(true);
        when(results.decode(stored, addressId)).thenReturn(original);
        assertEquals(original, service.execute(command).await().atMost(Duration.ofSeconds(2)));
        verifyNoInteractions(companies, sites, events, countries, divisions);
        assertEquals(0, atomic.writes);
    }

    @Test
    void deadlineRejectsNonpositiveNullAndOverflowBudgets() {
        for (var invalid : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(Long.MAX_VALUE)}) {
            assertThrows(IllegalArgumentException.class, () -> AddressDeadline.validate(invalid));
        }
        assertEquals(10_000_000_000L, AddressDeadline.validate(Duration.ofSeconds(10)));
        var deadline = new AddressDeadline(Duration.ofSeconds(10));
        assertTrue(deadline.remaining().compareTo(Duration.ZERO) > 0);
        assertTrue(deadline.remaining().compareTo(Duration.ofSeconds(10)) < 0);
    }

    @Test
    void lostAcceptedResponseRecoversOnlyAfterCloseWithDecreasingBudgetAndNoSecondWrite() {
        service();
        var stored = new java.util.concurrent.atomic.AtomicReference<CompletionRecord>();
        var accepted = new java.util.concurrent.atomic.AtomicReference<AddressMutation.Result>();
        when(completions.find(any())).thenAnswer(_ -> Uni.createFrom().item(stored.get()));
        when(completions.insert(any())).thenAnswer(call -> {
            var completion = call.getArgument(0, CompletionRecord.class); stored.set(completion); return Uni.createFrom().item(completion);
        });
        when(results.encode(any(), any())).thenAnswer(call -> { accepted.set(call.getArgument(1)); return Map.of("original", true); });
        when(results.decode(any(), any())).thenAnswer(_ -> accepted.get());
        when(fingerprints.matches(any(), anyShort(), anyString(), anyString())).thenReturn(true);
        var checkpoints = new ArrayList<Duration>();
        var measured = new AtomicOperation() {
            @Override public <T> Uni<T> read(Supplier<Uni<T>> work) { assertEquals(0, atomic.open); return atomic.read(work); }
            @Override public <T> Uni<T> execute(Supplier<Uni<T>> work) {
                return atomic.execute(work).chain(_ -> Uni.createFrom().failure(new PersistenceFailure(PersistenceFailure.Kind.UNAVAILABLE, null)));
            }
            @Override public <T> Uni<T> read(Duration remaining, Supplier<Uni<T>> work) { checkpoints.add(remaining); return read(work); }
            @Override public <T> Uni<T> execute(Duration remaining, Supplier<Uni<T>> work) { checkpoints.add(remaining); return execute(work); }
        };
        var service = new MutateAddress(new MutateAddress.Storage(measured, companies, sites, events),
                new MutateAddress.Idempotency(completions, fingerprints, results), new AddressGeography(countries, divisions),
                () -> UUID.fromString("019a2f00-0000-7000-8000-000000000010"), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(5));
        var empty = AddressInformationPatch.empty();
        var patch = new AddressInformationPatch(empty.addressType(), new AddressInformationPatch.TextChanges(FieldChange.absent(),
                FieldChange.present("NEW"), FieldChange.absent(), FieldChange.absent(), FieldChange.absent(), FieldChange.absent()), empty.isPrimary(), empty.coordinates());
        assertEquals(1, service.execute(command(AddressMutation.Operation.UPDATE, patch, "lost")).await().atMost(Duration.ofSeconds(2)).siteVersion());
        assertEquals(1, atomic.writes); assertEquals(2, atomic.reads); assertEquals(0, atomic.open);
        assertEquals(3, checkpoints.size());
        assertTrue(checkpoints.get(1).compareTo(checkpoints.getFirst()) <= 0);
        assertTrue(checkpoints.getLast().compareTo(checkpoints.get(1)) <= 0);
        verify(events, times(1)).store(any());
    }

    private void state(CompanyStatus companyStatus, SiteStatus siteStatus, long version) {
        var audit = new Audit(NOW.minusSeconds(1), "Creator", NOW.minusSeconds(1), "Creator");
        when(companies.findForUpdate(scope.tenantId(), scope.companyId())).thenReturn(Uni.createFrom().item(new CompanyRecord(
                new Company(scope.companyId(), scope.tenantId(), UUID.randomUUID(), companyStatus, AggregateVersion.initial()), "CODE", "NAME", null, null, audit)));
        when(sites.findForUpdate(scope.tenantId(), scope.companyId(), scope.siteId())).thenReturn(Uni.createFrom().item(new SiteRecord(
                new Site(scope.siteId(), scope.tenantId(), scope.companyId(), SiteType.BRANCH, siteStatus, new AggregateVersion(version)), "SITE", "NAME", audit)));
    }

    private AddressRecord address() {
        return new AddressRecord(addressId, scope.siteId(), AddressType.PHYSICAL, "EC", null, "AVENIDA", null, null, null, null, null,
                false, new Audit(NOW.minusSeconds(1), "Creator", NOW.minusSeconds(1), "Creator"), null, null);
    }

    private AddressMutation.Command command(AddressMutation.Operation operation, AddressInformationPatch patch, String key) {
        return new AddressMutation.Command(context, scope, 0, key, new AddressMutation.Intent(operation,
                operation == AddressMutation.Operation.CREATE ? null : addressId, patch));
    }

    private static void assertFailure(MutateAddress service, AddressMutation.Command command, AddressFailure.Kind kind) {
        var awaitable = service.execute(command).await();
        var timeout = Duration.ofSeconds(2);
        assertEquals(kind, assertThrows(AddressFailure.class, () -> awaitable.atMost(timeout)).kind());
    }
}
