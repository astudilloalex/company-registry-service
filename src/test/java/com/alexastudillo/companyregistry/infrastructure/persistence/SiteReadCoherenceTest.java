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
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class SiteReadCoherenceTest {
    @Inject
    AtomicOperation atomic;
    @Inject
    CompanyRepository companies;
    @Inject
    SiteRepository sites;
    @Inject
    SiteCursors cursors;
    @Inject
    Identifiers ids;
    @Inject
    io.vertx.mutiny.sqlclient.Pool pool;

    @Test
    @RunOnVertxContext
    void joinedParentStateDoesNotReuseTheExistenceLookupsManagedEntity(UniAsserter a) {
        for (var detail : List.of(true, false)) {
            var context = new OperationContext(UUID.randomUUID(), "Actor", UUID.randomUUID().toString());
            var companyId = ids.next();
            var siteId = ids.next();
            var now = Instant.parse("2026-10-17T10:00:00.123456Z");
            var audit = new Audit(now, "Creator", now, "Creator");
            var parent = new CompanyRecord(new Company(companyId, context.tenantId(), UUID.randomUUID(),
                    CompanyStatus.DRAFT, AggregateVersion.initial()), "COMPANY", "COMPANY", null, null, audit);
            var site = new SiteRecord(new Site(siteId, context.tenantId(), companyId, SiteType.BRANCH,
                    SiteStatus.ACTIVE, AggregateVersion.initial()), "SITE", "SITE", audit);
            a.execute(() -> atomic.execute(() -> companies.insert(parent).chain(() -> sites.insert(site))));
            var changing = mock(CompanyRepository.class, delegatesTo(companies));
            doAnswer(invocation -> companies.find(invocation.getArgument(0), invocation.getArgument(1))
                    .call(_ -> pool.preparedQuery("UPDATE companies SET status='ACTIVE' WHERE id=$1")
                            .execute(io.vertx.mutiny.sqlclient.Tuple.of(companyId))))
                    .when(changing).find(any(), any());
            var reads = new ReadSites(atomic, changing, sites, cursors);
            if (detail)
                a.assertThat(() -> reads.find(context, companyId, siteId),
                        row -> assertTrue(row.effectivelyOperational()));
            else
                a.assertThat(
                        () -> reads.page(context, companyId,
                                new SiteSelection.Request(new SiteSelection.Filters(null, null, null), 50, null)),
                        page -> assertTrue(page.data().getFirst().effectivelyOperational()));
        }
    }
}
