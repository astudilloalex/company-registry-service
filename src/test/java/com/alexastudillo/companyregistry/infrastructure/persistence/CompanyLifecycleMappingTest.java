package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.domain.model.*;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
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
class CompanyLifecycleMappingTest {
    @Inject CompanyRepository companies;
    @Inject SiteRepository sites;
    @Inject AtomicOperation atomic;
    @Inject UuidV7Generator ids;

    private CompanyRecord original() {
        var now = Instant.parse("2026-10-18T12:00:00.123456Z");
        return new CompanyRecord(new Company(ids.next(), UUID.randomUUID(), UUID.randomUUID(), CompanyStatus.DRAFT,
                AggregateVersion.initial()), "CODE", "COMPANY", "DESCRIPTION", "https://host/LogoAb.png",
                new Audit(now, "Creator-aB", now, "Creator-aB"));
    }

    private CompanyRecord changed(CompanyRecord row, UUID tenant, CompanyStatus target) {
        return new CompanyRecord(new Company(row.company().id(), tenant, row.company().partyId(), target,
                row.company().version().next()), row.code(), row.operatingName(), row.description(), row.logoUrl(),
                new Audit(row.audit().createdAt(), row.audit().createdBy(), row.audit().updatedAt().plusSeconds(1), "Editor-aB"));
    }

    @Test @RunOnVertxContext
    void conditionallyWritesOnlyStatusAuditAndOneRevisionFromAFreshSession(UniAsserter a) {
        var row = original();
        var tenant = row.company().tenantId();
        var id = row.company().id();
        var next = changed(row, tenant, CompanyStatus.ACTIVE);
        var child = new SiteRecord(new Site(ids.next(), tenant, id, SiteType.BRANCH, SiteStatus.DRAFT,
                AggregateVersion.initial()), "SITE", "SITE", row.audit());
        a.execute(() -> atomic.execute(() -> companies.insert(row).chain(() -> sites.insert(child))));
        a.assertEquals(() -> atomic.execute(() -> companies.findForUpdate(tenant, id)
                .chain(locked -> companies.transition(next, 0, locked.company().status()))), next);
        a.assertEquals(() -> atomic.read(() -> companies.find(tenant, id)), next);
        a.assertEquals(() -> atomic.read(() -> sites.find(tenant, id, child.site().id())), child);
        a.assertFailedWith(() -> atomic.execute(() -> companies.transition(next, 0, CompanyStatus.DRAFT)),
                f -> assertEquals(PersistenceFailure.Kind.STALE_VERSION, ((PersistenceFailure) f).kind()));
        var foreign = changed(row, UUID.randomUUID(), CompanyStatus.ACTIVE);
        a.assertFailedWith(() -> atomic.execute(() -> companies.transition(foreign, 0, CompanyStatus.DRAFT)),
                f -> assertEquals(PersistenceFailure.Kind.STALE_VERSION, ((PersistenceFailure) f).kind()));
        a.assertFailedWith(() -> atomic.execute(() -> companies.transition(next, 0, CompanyStatus.INACTIVE)),
                f -> assertEquals(PersistenceFailure.Kind.STALE_VERSION, ((PersistenceFailure) f).kind()));
        a.assertEquals(() -> atomic.read(() -> companies.find(tenant, id)), next);
    }

    @Test
    void rejectsOverflowAndInconsistentRevisionBeforeSubscribingAnyWrite() {
        var row = original();
        var next = changed(row, row.company().tenantId(), CompanyStatus.ACTIVE);
        assertThrows(IllegalArgumentException.class, () -> companies.transition(next, Long.MAX_VALUE, CompanyStatus.DRAFT));
        assertThrows(IllegalArgumentException.class, () -> companies.transition(next, 1, CompanyStatus.DRAFT));
        assertThrows(IllegalArgumentException.class, () -> companies.transition(next, 0, CompanyStatus.ARCHIVED));
    }

    @Test @RunOnVertxContext
    void resolvesOnlyCurrentQualifiedHeadquartersWithoutMutatingAnySite(UniAsserter a) {
        for (var status : SiteStatus.values()) {
            var row = original();
            var tenant = row.company().tenantId();
            var company = row.company().id();
            var history = new SiteRecord(new Site(ids.next(), tenant, company, SiteType.HEADQUARTERS, SiteStatus.CLOSED,
                    AggregateVersion.initial()), "HISTORY", "HISTORY", row.audit());
            var current = new SiteRecord(new Site(ids.next(), tenant, company, SiteType.HEADQUARTERS, status,
                    AggregateVersion.initial()), "CURRENT", "CURRENT", row.audit());
            a.execute(() -> atomic.execute(() -> companies.insert(row).chain(() -> sites.insert(history))));
            a.assertNull(() -> atomic.execute(() -> companies.findForUpdate(tenant, company)
                    .chain(() -> sites.currentHeadquartersForUpdate(tenant, company))));
            a.execute(() -> atomic.execute(() -> companies.findForUpdate(tenant, company).chain(() -> sites.insert(current))));
            a.assertEquals(() -> atomic.execute(() -> companies.findForUpdate(tenant, company)
                    .chain(() -> sites.currentHeadquartersForUpdate(tenant, company))), status == SiteStatus.CLOSED ? null : current);
            a.assertNull(() -> atomic.execute(() -> sites.currentHeadquartersForUpdate(UUID.randomUUID(), company)));
            a.assertNull(() -> atomic.execute(() -> sites.currentHeadquartersForUpdate(tenant, UUID.randomUUID())));
            a.assertEquals(() -> atomic.read(() -> sites.find(tenant, company, current.site().id())), current);
            a.assertEquals(() -> atomic.read(() -> sites.find(tenant, company, history.site().id())), history);
            a.assertEquals(() -> atomic.read(() -> companies.find(tenant, company)), row);
        }
    }
}
