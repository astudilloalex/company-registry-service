package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Assertions;

import com.alexastudillo.companyregistry.application.persistence.AtomicOperation;
import com.alexastudillo.companyregistry.application.persistence.CompanyRepository;
import com.alexastudillo.companyregistry.application.persistence.Identifiers;
import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.Audit;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompanyRecord;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.SiteRecord;
import com.alexastudillo.companyregistry.application.persistence.SiteRepository;
import com.alexastudillo.companyregistry.domain.model.AggregateVersion;
import com.alexastudillo.companyregistry.domain.model.Company;
import com.alexastudillo.companyregistry.domain.model.CompanyStatus;
import com.alexastudillo.companyregistry.domain.model.Site;
import com.alexastudillo.companyregistry.domain.model.SiteStatus;
import com.alexastudillo.companyregistry.domain.model.SiteType;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class SiteMutationPersistenceTest {
    @Inject AtomicOperation atomic;
    @Inject CompanyRepository companies;
    @Inject SiteRepository sites;
    @Inject Identifiers ids;
    @Inject io.vertx.mutiny.sqlclient.Pool pool;

    @Test
    @RunOnVertxContext
    void qualifiedLockAndBulkUpdateChangeOnlyOneSiteRevision(UniAsserter a) {
        var parent = parent();
        var original = site(parent, "A", SiteType.BRANCH, SiteStatus.DRAFT);
        var changed = changed(original, "B", SiteType.WAREHOUSE, SiteStatus.ACTIVE);
        a.execute(() -> atomic.execute(() -> companies.insert(parent).chain(() -> sites.insert(original))));
        a.assertThat(() -> atomic.execute(() -> companies.findForUpdate(parent.company().tenantId(), parent.company().id())
                .chain(() -> sites.findForUpdate(original.site().tenantId(), original.site().companyId(), original.site().id()))
                .chain(locked -> {
                    assertEquals(original, locked);
                    return sites.update(changed, 0);
                })), row -> assertEquals(changed, row));
        a.assertThat(() -> atomic.read(() -> sites.find(original.site().tenantId(), original.site().companyId(), original.site().id())),
                row -> {
                    assertEquals(changed, row);
                    assertEquals(1, row.site().version().value());
                    assertEquals(original.audit().createdAt(), row.audit().createdAt());
                    assertEquals(original.audit().createdBy(), row.audit().createdBy());
                });
        a.assertThat(() -> atomic.read(() -> companies.find(parent.company().tenantId(), parent.company().id())),
                row -> assertEquals(parent, row));
        a.assertThat(() -> atomic.execute(() -> sites.findForUpdate(UUID.randomUUID(), original.site().companyId(), original.site().id())),
                Assertions::assertNull);
        a.assertThat(() -> atomic.execute(() -> sites.findForUpdate(original.site().tenantId(), ids.next(), original.site().id())),
                Assertions::assertNull);
        a.assertFailedWith(() -> atomic.execute(() -> sites.update(changed, 0)), failure ->
                assertEquals(PersistenceFailure.Kind.STALE_VERSION, ((PersistenceFailure) failure).kind()));
        var wrongOwner = new SiteRecord(new Site(original.site().id(), UUID.randomUUID(), original.site().companyId(),
                SiteType.BRANCH, SiteStatus.ACTIVE, new AggregateVersion(1)), "ALIEN", "ALIEN", changed.audit());
        a.assertFailedWith(() -> atomic.execute(() -> sites.update(wrongOwner, 0)), failure ->
                assertEquals(PersistenceFailure.Kind.STALE_VERSION, ((PersistenceFailure) failure).kind()));
        a.assertThat(() -> atomic.read(() -> sites.find(original.site().tenantId(), original.site().companyId(), original.site().id())),
                row -> assertEquals(changed, row));
    }

    @Test
    @RunOnVertxContext
    void diagnosticsExcludeSelfAndRollbackPreservesTheOriginalCodeReservation(UniAsserter a) {
        var parent = parent();
        var original = site(parent, "A", SiteType.HEADQUARTERS, SiteStatus.DRAFT);
        var closed = site(parent, "CLOSED", SiteType.BRANCH, SiteStatus.CLOSED);
        a.execute(() -> atomic.execute(() -> companies.insert(parent)
                .chain(() -> sites.insert(original)).chain(() -> sites.insert(closed))));
        a.assertThat(() -> atomic.read(() -> sites.codeOccupiedByOther(original.site().tenantId(), original.site().companyId(), original.site().id(), "A")),
                Assertions::assertFalse);
        a.assertThat(() -> atomic.read(() -> sites.headquartersOccupiedByOther(original.site().tenantId(), original.site().companyId(), original.site().id())),
                Assertions::assertFalse);
        a.assertThat(() -> atomic.read(() -> sites.headquartersOccupiedByOther(closed.site().tenantId(), closed.site().companyId(), closed.site().id())),
                Assertions::assertTrue);
        a.assertThat(() -> atomic.read(() -> sites.codeOccupiedByOther(original.site().tenantId(), original.site().companyId(), original.site().id(), "CLOSED")),
                Assertions::assertTrue);
        a.assertThat(() -> atomic.read(() -> sites.codeOccupiedByOther(UUID.randomUUID(), original.site().companyId(), original.site().id(), "CLOSED")),
                Assertions::assertFalse);
        var renamed = changed(original, "B", SiteType.HEADQUARTERS, SiteStatus.DRAFT);
        a.assertFailedWith(() -> atomic.execute(() -> companies.findForUpdate(parent.company().tenantId(), parent.company().id())
                .chain(() -> sites.findForUpdate(original.site().tenantId(), original.site().companyId(), original.site().id()))
                .chain(() -> sites.update(renamed, 0)).chain(() -> Uni.createFrom().failure(new IllegalStateException("rollback")))),
                failure -> assertEquals("rollback", failure.getMessage()));
        a.assertThat(() -> atomic.read(() -> sites.codeOccupied(original.site().tenantId(), original.site().companyId(), "A")),
                Assertions::assertTrue);
        a.assertThat(() -> atomic.read(() -> sites.codeOccupied(original.site().tenantId(), original.site().companyId(), "B")),
                Assertions::assertFalse);
        a.execute(() -> atomic.execute(() -> companies.findForUpdate(parent.company().tenantId(), parent.company().id())
                .chain(() -> sites.findForUpdate(original.site().tenantId(), original.site().companyId(), original.site().id()))
                .chain(() -> sites.update(renamed, 0))
                .call(() -> pool.preparedQuery("SELECT code FROM company_sites WHERE tenant_id=$1 AND company_id=$2 AND id=$3")
                        .execute(io.vertx.mutiny.sqlclient.Tuple.of(original.site().tenantId(), original.site().companyId(), original.site().id()))
                        .invoke(rows -> assertEquals("A", rows.iterator().next().getString("code"))))));
        a.assertThat(() -> atomic.read(() -> sites.codeOccupied(original.site().tenantId(), original.site().companyId(), "A")),
                Assertions::assertFalse);
        a.assertThat(() -> atomic.read(() -> sites.codeOccupied(original.site().tenantId(), original.site().companyId(), "B")),
                Assertions::assertTrue);
    }

    private CompanyRecord parent() {
        var now = Instant.parse("2026-10-18T10:00:00.123456Z");
        var company = new Company(ids.next(), UUID.randomUUID(), UUID.randomUUID(), CompanyStatus.DRAFT, AggregateVersion.initial());
        return new CompanyRecord(company, "COMPANY", "COMPANY", null, null, new Audit(now, "Creator", now, "Creator"));
    }

    private SiteRecord site(CompanyRecord parent, String code, SiteType type, SiteStatus status) {
        return new SiteRecord(new Site(ids.next(), parent.company().tenantId(), parent.company().id(), type, status,
                AggregateVersion.initial()), code, "SITE", parent.audit());
    }

    private SiteRecord changed(SiteRecord original, String code, SiteType type, SiteStatus status) {
        var site = original.site();
        return new SiteRecord(new Site(site.id(), site.tenantId(), site.companyId(), type, status, site.version().next()),
                code, "CHANGED", new Audit(original.audit().createdAt(), original.audit().createdBy(),
                        Instant.parse("2026-10-18T11:00:00.654321Z"), " Actor-aB "));
    }
}
