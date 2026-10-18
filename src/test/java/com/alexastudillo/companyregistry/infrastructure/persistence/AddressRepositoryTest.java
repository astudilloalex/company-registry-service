package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alexastudillo.companyregistry.application.persistence.CompanyRepository;
import com.alexastudillo.companyregistry.application.persistence.CompletionRepository;
import com.alexastudillo.companyregistry.application.persistence.SiteRepository;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.application.site.AddressScope;
import com.alexastudillo.companyregistry.application.site.AddressSelection;
import com.alexastudillo.companyregistry.application.site.AddressMutation;
import com.alexastudillo.companyregistry.application.site.AddressResults;
import com.alexastudillo.companyregistry.domain.model.*;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class AddressRepositoryTest {
    @Inject
    CompanyRepository companies;
    @Inject
    SiteRepository sites;
    @Inject
    UuidV7Generator ids;
    @Inject
    CompletionRepository completions;
    @Inject
    AddressResults results;

    @Test
    @RunOnVertxContext
    void acceptedAddressAndReceiptRoundTripRealJsonbIncludingNumbersAndNulls(UniAsserter a) {
        var fixture = fixture();
        var scope = fixture.scope();
        var row = address(scope, false, AddressType.PHYSICAL, false);
        var coordinates = new AddressCoordinates(new java.math.BigDecimal("0.000001"), new java.math.BigDecimal("90"));
        var numeric = new AddressRecord(row.id(), row.siteId(), row.type(), row.countryCode(), null, row.line1(), null,
                null, null,
                coordinates.latitude(), coordinates.longitude(), false, row.audit(), null, null);
        var view = new AddressMutation.View(scope, numeric, 1);
        var receipt = new AddressMutation.Deletion(scope, row.id(), 2, row.audit().updatedAt(),
                row.audit().updatedBy());
        var createKey = new CompletionKey(scope.tenantId(), AddressMutation.Operation.CREATE.namespace(), "create");
        var deleteKey = new CompletionKey(scope.tenantId(), AddressMutation.Operation.DELETE.namespace(), "delete");
        var create = new CompletionRecord(createKey, "a".repeat(64), scope.companyId(), scope.siteId(), 0L, (short) 201,
                (short) 1,
                results.encode(AddressMutation.Operation.CREATE, view), row.audit().updatedAt(),
                row.audit().updatedBy(), (short) 1, "K1");
        var delete = new CompletionRecord(deleteKey, "b".repeat(64), scope.companyId(), scope.siteId(), 1L, (short) 200,
                (short) 1,
                results.encode(AddressMutation.Operation.DELETE, receipt), row.audit().updatedAt(),
                row.audit().updatedBy(), (short) 1, "K1");
        a.execute(() -> Panache
                .withTransaction(() -> companies.insert(fixture.company()).chain(() -> sites.insert(fixture.site()))
                        .chain(() -> completions.insert(create)).chain(() -> completions.insert(delete))));
        a.assertEquals(() -> Panache.withSession(() -> completions.find(createKey))
                .map(stored -> results.decode(stored, null)), view);
        a.assertEquals(() -> Panache.withSession(() -> completions.find(deleteKey))
                .map(stored -> results.decode(stored, row.id())), receipt);
    }

    @Test
    @RunOnVertxContext
    void qualifiedCurrentReadsFilterBeforeLimitAndUseDescendingIdTieBreak(UniAsserter a) {
        var fixture = fixture();
        var scope = fixture.scope();
        var low = address(scope, false, AddressType.PHYSICAL, false);
        var high = address(scope, true, AddressType.MAILING, false);
        var deleted = address(scope, false, AddressType.PHYSICAL, true);
        var ordered = java.util.stream.Stream.of(low, high)
                .sorted(java.util.Comparator.comparing(AddressRecord::id).reversed()).toList();
        a.execute(() -> Panache.withTransaction(() -> companies.insert(fixture.company())
                .chain(() -> sites.insert(fixture.site()))
                .chain(() -> sites.insertAddress(scope.tenantId(), scope.companyId(), low))
                .chain(() -> sites.insertAddress(scope.tenantId(), scope.companyId(), high))
                .chain(() -> sites.insertAddress(scope.tenantId(), scope.companyId(), deleted))));
        var filters = new AddressSelection.Filters(null, null);
        a.assertThat(() -> Panache.withSession(() -> sites.addressPage(scope, filters, null, 1)), page -> {
            assertEquals(2, page.size());
            assertEquals(ordered.getFirst().id(), page.getFirst().addressId());
            assertEquals(ordered.getLast().id(), page.getLast().addressId());
            assertTrue(page.stream().allMatch(row -> row.siteVersion() == 0));
        });
        var boundary = new AddressSelection.Boundary(ordered.getFirst().audit().createdAt(), ordered.getFirst().id());
        a.assertThat(() -> Panache.withSession(() -> sites.addressPage(scope, filters, boundary, 1)), page -> {
            assertEquals(1, page.size());
            assertEquals(ordered.getLast().id(), page.getFirst().addressId());
        });
        a.assertThat(() -> Panache.withSession(() -> sites.addressPage(scope,
                new AddressSelection.Filters(AddressType.PHYSICAL, true), null, 1)),
                page -> assertTrue(page.isEmpty()));
        a.assertThat(() -> Panache.withSession(() -> sites.addressPage(scope,
                new AddressSelection.Filters(AddressType.MAILING, true), null, 1)), page -> {
                    assertEquals(1, page.size());
                    assertEquals(high.id(), page.getFirst().addressId());
                });
        a.assertNull(() -> Panache.withSession(() -> sites.addressDetail(scope, deleted.id())));
        for (var foreign : new AddressScope[] { new AddressScope(UUID.randomUUID(), scope.companyId(), scope.siteId()),
                new AddressScope(scope.tenantId(), UUID.randomUUID(), scope.siteId()),
                new AddressScope(scope.tenantId(), scope.companyId(), UUID.randomUUID()) }) {
            a.assertNull(() -> Panache.withSession(() -> sites.addressDetail(foreign, high.id())));
            a.assertThat(() -> Panache.withSession(() -> sites.addressPage(foreign, filters, null, 50)),
                    page -> assertTrue(page.isEmpty()));
        }
    }

    @Test
    @RunOnVertxContext
    void demotionPrecedesReclassificationAndSiteAdvancesOnlyOnceWithoutChangingCompany(UniAsserter a) {
        var fixture = fixture();
        var scope = fixture.scope();
        var physical = address(scope, true, AddressType.PHYSICAL, false);
        var mailing = address(scope, true, AddressType.MAILING, false);
        var now = physical.audit().createdAt().plusSeconds(1);
        var demoted = changed(mailing, AddressType.MAILING, false, now, false);
        var promoted = changed(physical, AddressType.MAILING, true, now, false);
        var before = fixture.site();
        var updated = new SiteRecord(new Site(scope.siteId(), scope.tenantId(), scope.companyId(), before.site().type(),
                before.site().status(), before.site().version().next()), before.code(), before.name(),
                new Audit(before.audit().createdAt(), before.audit().createdBy(), now, "Editor-aB"));
        a.execute(() -> Panache
                .withTransaction(() -> companies.insert(fixture.company()).chain(() -> sites.insert(before))
                        .chain(() -> sites.insertAddress(scope.tenantId(), scope.companyId(), physical))
                        .chain(() -> sites.insertAddress(scope.tenantId(), scope.companyId(), mailing))));
        a.execute(() -> Panache.withTransaction(() -> companies.findForUpdate(scope.tenantId(), scope.companyId())
                .chain(() -> sites.findForUpdate(scope.tenantId(), scope.companyId(), scope.siteId()))
                .chain(() -> sites.updateAddress(scope, demoted)).chain(() -> sites.updateAddress(scope, promoted))
                .chain(() -> sites.update(updated, 0))));
        a.assertThat(() -> Panache.withSession(() -> sites.addressDetail(scope, physical.id())), result -> {
            assertEquals(1, result.siteVersion());
            assertEquals(AddressType.MAILING, result.address().type());
            assertTrue(result.address().primary());
            assertEquals(promoted.audit(), result.address().audit());
        });
        a.assertThat(() -> Panache.withSession(() -> sites.addressDetail(scope, mailing.id())), result -> {
            assertEquals(1, result.siteVersion());
            assertFalse(result.address().primary());
            assertEquals(demoted.audit(), result.address().audit());
        });
        a.assertNull(() -> Panache.withSession(() -> sites.primaryAddress(scope, AddressType.PHYSICAL)));
        a.assertEquals(() -> Panache.withSession(() -> companies.find(scope.tenantId(), scope.companyId())),
                fixture.company());
        a.assertEquals(() -> Panache.withSession(() -> sites.find(scope.tenantId(), scope.companyId(), scope.siteId())),
                updated);
        var removed = changed(promoted, AddressType.MAILING, false, now.plusSeconds(1), true);
        a.execute(() -> Panache.withTransaction(() -> sites.updateAddress(scope, removed)));
        a.assertNull(() -> Panache.withSession(() -> sites.addressDetail(scope, removed.id())));
        a.assertThat(
                () -> Panache.withSession(() -> sites.addresses(scope.tenantId(), scope.companyId(), scope.siteId())),
                rows -> {
                    var historical = rows.stream().filter(row -> row.id().equals(removed.id())).findFirst()
                            .orElseThrow();
                    assertEquals(removed.deletedAt(), historical.deletedAt());
                    assertEquals(physical.audit().createdAt(), historical.audit().createdAt());
                    assertEquals(physical.audit().createdBy(), historical.audit().createdBy());
                });
    }

    private record Fixture(AddressScope scope, CompanyRecord company, SiteRecord site) {
    }

    private Fixture fixture() {
        var scope = new AddressScope(UUID.randomUUID(), ids.next(), ids.next());
        var now = Instant.parse("2026-10-18T20:00:00.123456Z");
        var audit = new Audit(now, "Creator-aB", now, "Creator-aB");
        return new Fixture(scope, new CompanyRecord(new Company(scope.companyId(), scope.tenantId(), UUID.randomUUID(),
                CompanyStatus.DRAFT, AggregateVersion.initial()), "COMPANY", "NAME", null, null, audit),
                new SiteRecord(
                        new Site(scope.siteId(), scope.tenantId(), scope.companyId(), SiteType.BRANCH, SiteStatus.DRAFT,
                                AggregateVersion.initial()),
                        "SITE", "NAME", audit));
    }

    private AddressRecord address(AddressScope scope, boolean primary, AddressType type, boolean deleted) {
        var now = Instant.parse("2026-10-18T20:00:00.123456Z");
        return new AddressRecord(ids.next(), scope.siteId(), type, "EC", null, "AVENIDA", null, null, null, null, null,
                primary, new Audit(now, "Creator-aB", now, "Creator-aB"), deleted ? now : null,
                deleted ? "Creator-aB" : null);
    }

    private AddressRecord changed(AddressRecord row, AddressType type, boolean primary, Instant now, boolean deleted) {
        return new AddressRecord(row.id(), row.siteId(), type, row.countryCode(), row.administrativeDivisionCode(),
                row.line1(),
                row.line2(), row.houseNumber(), row.postalCode(), row.latitude(), row.longitude(), primary,
                new Audit(row.audit().createdAt(), row.audit().createdBy(), now, "Editor-aB"), deleted ? now : null,
                deleted ? "Editor-aB" : null);
    }
}
