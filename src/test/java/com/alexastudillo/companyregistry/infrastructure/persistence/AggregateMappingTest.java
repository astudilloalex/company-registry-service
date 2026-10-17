package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
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
class AggregateMappingTest {
    @Inject CompanyRepository companies;
    @Inject SiteRepository sites;
    @Inject UuidV7Generator ids;

    @Test @RunOnVertxContext
    void roundTripNamedEnumsInstantInitialVersionAndQualifiedQueries(UniAsserter asserter) {
        var tenant = UUID.randomUUID(); var id = ids.next(); var siteId = ids.next();
        var now = Instant.parse("2026-10-15T12:30:45.123456Z");
        var audit = new Audit(now, "Actor-aB", now, "Actor-aB");
        var company = new CompanyRecord(new Company(id, tenant, UUID.randomUUID(), CompanyStatus.DRAFT, AggregateVersion.initial()),
                "COMPAÑÍA", "COMPAÑÍA  ÁGIL", "DESCRIPCIÓN", "https://host/LogoAb.png", audit);
        var site = new SiteRecord(new Site(siteId, tenant, id, SiteType.HEADQUARTERS, SiteStatus.ACTIVE, AggregateVersion.initial()), "LEGAL", "SEDE LEGAL", audit);
        assertEquals(7, id.version()); assertEquals(2, id.variant()); assertEquals(7, siteId.version());
        asserter.assertEquals(() -> Panache.withTransaction(() -> companies.insert(company).chain(() -> sites.insert(site))), site);
        asserter.assertEquals(() -> Panache.withSession(() -> companies.find(tenant, id)), company);
        asserter.assertEquals(() -> Panache.withSession(() -> sites.find(tenant, id, siteId)), site);
        asserter.assertNull(() -> Panache.withSession(() -> companies.find(UUID.randomUUID(), id)));
        asserter.assertNull(() -> Panache.withSession(() -> sites.find(UUID.randomUUID(), id, siteId)));
        asserter.assertNull(() -> Panache.withSession(() -> sites.find(tenant, UUID.randomUUID(), siteId)));
    }
}
