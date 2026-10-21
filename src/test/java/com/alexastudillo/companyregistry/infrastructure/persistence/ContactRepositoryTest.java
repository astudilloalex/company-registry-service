package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alexastudillo.companyregistry.application.persistence.CompanyRepository;
import com.alexastudillo.companyregistry.application.persistence.CompletionRepository;
import com.alexastudillo.companyregistry.application.persistence.SiteRepository;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.application.site.ContactScope;
import com.alexastudillo.companyregistry.application.site.ContactSelection;
import com.alexastudillo.companyregistry.application.site.ContactMutation;
import com.alexastudillo.companyregistry.application.site.ContactResults;
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
class ContactRepositoryTest {
    @Inject
    CompanyRepository companies;
    @Inject
    SiteRepository sites;
    @Inject
    UuidV7Generator ids;
    @Inject
    CompletionRepository completions;
    @Inject
    ContactResults results;

    @Test
    @RunOnVertxContext
    void acceptedContactAndReceiptRoundTripRealJsonbIncludingNulls(UniAsserter a) {
        var fixture = fixture();
        var scope = fixture.scope();
        var row = contact(scope, false, ContactType.MOBILE, false);
        var numeric = row;
        var view = new ContactMutation.View(scope, numeric, 1);
        var receipt = new ContactMutation.Deletion(scope, row.id(), 2, row.audit().updatedAt(),
                row.audit().updatedBy());
        var createKey = new CompletionKey(scope.tenantId(), ContactMutation.Operation.CREATE.namespace(),
                "create");
        var deleteKey = new CompletionKey(scope.tenantId(), ContactMutation.Operation.DELETE.namespace(),
                "delete");
        var create = new CompletionRecord(createKey, "a".repeat(64), scope.companyId(), scope.siteId(), 0L,
                (short) 201,
                (short) 1,
                results.encode(ContactMutation.Operation.CREATE, view), row.audit().updatedAt(),
                row.audit().updatedBy(), (short) 1, "K1");
        var delete = new CompletionRecord(deleteKey, "b".repeat(64), scope.companyId(), scope.siteId(), 1L,
                (short) 200,
                (short) 1,
                results.encode(ContactMutation.Operation.DELETE, receipt), row.audit().updatedAt(),
                row.audit().updatedBy(), (short) 1, "K1");
        a.execute(() -> Panache
                .withTransaction(() -> companies.insert(fixture.company())
                        .chain(() -> sites.insert(fixture.site()))
                        .chain(() -> completions.insert(create))
                        .chain(() -> completions.insert(delete))));
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
        var low = contact(scope, false, ContactType.MOBILE, false);
        var high = contact(scope, true, ContactType.WHATSAPP, false);
        var deleted = contact(scope, false, ContactType.MOBILE, true);
        var ordered = java.util.stream.Stream.of(low, high)
                .sorted(java.util.Comparator.comparing(ContactRecord::id).reversed()).toList();
        a.execute(() -> Panache.withTransaction(() -> companies.insert(fixture.company())
                .chain(() -> sites.insert(fixture.site()))
                .chain(() -> sites.insertContact(scope.tenantId(), scope.companyId(), low))
                .chain(() -> sites.insertContact(scope.tenantId(), scope.companyId(), high))
                .chain(() -> sites.insertContact(scope.tenantId(), scope.companyId(), deleted))));
        var filters = new ContactSelection.Filters(null, null);
        a.assertThat(() -> Panache.withSession(() -> sites.contactPage(scope, filters, null, 1)), page -> {
            assertEquals(2, page.size());
            assertEquals(ordered.getFirst().id(), page.getFirst().contactId());
            assertEquals(ordered.getLast().id(), page.getLast().contactId());
            assertTrue(page.stream().allMatch(row -> row.siteVersion() == 0));
        });
        var boundary = new ContactSelection.Boundary(ordered.getFirst().audit().createdAt(),
                ordered.getFirst().id());
        a.assertThat(() -> Panache.withSession(() -> sites.contactPage(scope, filters, boundary, 1)), page -> {
            assertEquals(1, page.size());
            assertEquals(ordered.getLast().id(), page.getFirst().contactId());
        });
        a.assertThat(() -> Panache.withSession(() -> sites.contactPage(scope,
                new ContactSelection.Filters(ContactType.MOBILE, true), null, 1)),
                page -> assertTrue(page.isEmpty()));
        a.assertThat(() -> Panache.withSession(() -> sites.contactPage(scope,
                new ContactSelection.Filters(ContactType.WHATSAPP, true), null, 1)), page -> {
                    assertEquals(1, page.size());
                    assertEquals(high.id(), page.getFirst().contactId());
                });
        a.assertNull(() -> Panache.withSession(() -> sites.contactDetail(scope, deleted.id())));
        for (var foreign : new ContactScope[] {
                new ContactScope(UUID.randomUUID(), scope.companyId(), scope.siteId()),
                new ContactScope(scope.tenantId(), UUID.randomUUID(), scope.siteId()),
                new ContactScope(scope.tenantId(), scope.companyId(), UUID.randomUUID()) }) {
            a.assertNull(() -> Panache.withSession(() -> sites.contactDetail(foreign, high.id())));
            a.assertThat(() -> Panache.withSession(() -> sites.contactPage(foreign, filters, null, 50)),
                    page -> assertTrue(page.isEmpty()));
        }
    }

    @Test
    @RunOnVertxContext
    void demotionPrecedesReclassificationAndSiteAdvancesOnlyOnceWithoutChangingCompany(UniAsserter a) {
        var fixture = fixture();
        var scope = fixture.scope();
        var physical = contact(scope, true, ContactType.MOBILE, false);
        var mailing = contact(scope, true, ContactType.WHATSAPP, false);
        var now = physical.audit().createdAt().plusSeconds(1);
        var demoted = changed(mailing, ContactType.WHATSAPP, false, now, false);
        var promoted = changed(physical, ContactType.WHATSAPP, true, now, false);
        var before = fixture.site();
        var updated = new SiteRecord(
                new Site(scope.siteId(), scope.tenantId(), scope.companyId(), before.site().type(),
                        before.site().status(), before.site().version().next()),
                before.code(), before.name(),
                new Audit(before.audit().createdAt(), before.audit().createdBy(), now, "Editor-aB"));
        a.execute(() -> Panache
                .withTransaction(() -> companies.insert(fixture.company())
                        .chain(() -> sites.insert(before))
                        .chain(() -> sites.insertContact(scope.tenantId(), scope.companyId(),
                                physical))
                        .chain(() -> sites.insertContact(scope.tenantId(), scope.companyId(),
                                mailing))));
        a.execute(() -> Panache.withTransaction(() -> companies
                .findForUpdate(scope.tenantId(), scope.companyId())
                .chain(() -> sites.findForUpdate(scope.tenantId(), scope.companyId(), scope.siteId()))
                .chain(() -> sites.updateContact(scope, demoted))
                .chain(() -> sites.updateContact(scope, promoted))
                .chain(() -> sites.update(updated, 0))));
        a.assertThat(() -> Panache.withSession(() -> sites.contactDetail(scope, physical.id())), result -> {
            assertEquals(1, result.siteVersion());
            assertEquals(ContactType.WHATSAPP, result.contact().type());
            assertTrue(result.contact().primary());
            assertEquals(promoted.audit(), result.contact().audit());
        });
        a.assertThat(() -> Panache.withSession(() -> sites.contactDetail(scope, mailing.id())), result -> {
            assertEquals(1, result.siteVersion());
            assertFalse(result.contact().primary());
            assertEquals(demoted.audit(), result.contact().audit());
        });
        a.assertNull(() -> Panache.withSession(() -> sites.primaryContact(scope, ContactType.MOBILE)));
        a.assertEquals(() -> Panache.withSession(() -> companies.find(scope.tenantId(), scope.companyId())),
                fixture.company());
        a.assertEquals(() -> Panache
                .withSession(() -> sites.find(scope.tenantId(), scope.companyId(), scope.siteId())),
                updated);
        var removed = changed(promoted, ContactType.WHATSAPP, false, now.plusSeconds(1), true);
        a.execute(() -> Panache.withTransaction(() -> sites.updateContact(scope, removed)));
        a.assertNull(() -> Panache.withSession(() -> sites.contactDetail(scope, removed.id())));
        a.assertThat(
                () -> Panache.withSession(() -> sites.contacts(scope.tenantId(), scope.companyId(),
                        scope.siteId())),
                rows -> {
                    var historical = rows.stream().filter(row -> row.id().equals(removed.id()))
                            .findFirst()
                            .orElseThrow();
                    assertEquals(removed.deletedAt(), historical.deletedAt());
                    assertEquals(physical.audit().createdAt(), historical.audit().createdAt());
                    assertEquals(physical.audit().createdBy(), historical.audit().createdBy());
                });
    }

    @Test
    @RunOnVertxContext
    void duplicateChecksExcludeTargetAndKeepChannelsAndOwnershipIndependent(UniAsserter a) {
        var fixture = fixture();
        var scope = fixture.scope();
        var current = contact(scope, false, ContactType.MOBILE, false);
        var deleted = contact(scope, false, ContactType.MOBILE, true);
        a.execute(() -> Panache.withTransaction(() -> companies.insert(fixture.company())
                .chain(() -> sites.insert(fixture.site()))
                .chain(() -> sites.insertContact(scope.tenantId(), scope.companyId(), current))
                .chain(() -> sites.insertContact(scope.tenantId(), scope.companyId(), deleted))));
        a.assertTrue(() -> Panache.withSession(
                () -> sites.contactValueOccupiedByOther(scope, current.type(), current.value(), null)));
        a.assertFalse(() -> Panache.withSession(() -> sites.contactValueOccupiedByOther(scope, current.type(),
                current.value(), current.id())));
        a.assertFalse(() -> Panache.withSession(() -> sites.contactValueOccupiedByOther(scope,
                ContactType.WHATSAPP, current.value(), null)));
        var foreign = new ContactScope(UUID.randomUUID(), scope.companyId(), scope.siteId());
        a.assertFalse(() -> Panache.withSession(() -> sites.contactValueOccupiedByOther(foreign, current.type(),
                current.value(), null)));
        var reassigned = new ContactRecord(current.id(), UUID.randomUUID(), current.type(), current.value(),
                current.label(),
                current.primary(), current.audit(), null, null);
        a.assertFailedWith(() -> Panache.withTransaction(() -> sites.updateContact(scope, reassigned)),
                IllegalArgumentException.class);
    }

    private record Fixture(ContactScope scope, CompanyRecord company, SiteRecord site) {
    }

    private Fixture fixture() {
        var scope = new ContactScope(UUID.randomUUID(), ids.next(), ids.next());
        var now = Instant.parse("2026-10-18T20:00:00.123456Z");
        var audit = new Audit(now, "Creator-aB", now, "Creator-aB");
        return new Fixture(scope,
                new CompanyRecord(new Company(scope.companyId(), scope.tenantId(), UUID.randomUUID(),
                        CompanyStatus.DRAFT, AggregateVersion.initial()), "COMPANY", "NAME",
                        null, null, audit),
                new SiteRecord(
                        new Site(scope.siteId(), scope.tenantId(), scope.companyId(),
                                SiteType.BRANCH, SiteStatus.DRAFT,
                                AggregateVersion.initial()),
                        "SITE", "NAME", audit));
    }

    private ContactRecord contact(ContactScope scope, boolean primary, ContactType type, boolean deleted) {
        var now = Instant.parse("2026-10-18T20:00:00.123456Z");
        return new ContactRecord(ids.next(), scope.siteId(), type,
                type == ContactType.MOBILE ? "+12025550123" : "+447911123456", null,
                primary, new Audit(now, "Creator-aB", now, "Creator-aB"), deleted ? now : null,
                deleted ? "Creator-aB" : null);
    }

    private ContactRecord changed(ContactRecord row, ContactType type, boolean primary, Instant now,
            boolean deleted) {
        return new ContactRecord(row.id(), row.siteId(), type, row.value(), row.label(), primary,
                new Audit(row.audit().createdAt(), row.audit().createdBy(), now, "Editor-aB"),
                deleted ? now : null,
                deleted ? "Editor-aB" : null);
    }
}
