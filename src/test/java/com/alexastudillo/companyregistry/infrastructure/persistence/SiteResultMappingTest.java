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
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class SiteResultMappingTest {
    @Inject CompanyRepository companies;
    @Inject SiteRepository sites;
    @Inject CompletionRepository completions;
    @Inject SiteResultCodec codec;
    @Test @RunOnVertxContext void originalSiteSnapshotSurvivesRealJsonb(UniAsserter asserter) {
        var tenant = UUID.randomUUID(); var companyId = new UuidV7Generator().next();
        var result = SiteResultCodecTest.result(tenant, companyId);
        var parent = new CompanyRecord(new Company(companyId, tenant, UUID.randomUUID(), CompanyStatus.DRAFT,
                AggregateVersion.initial()), UUID.randomUUID().toString().toUpperCase(), "COMPANY", null, null, result.site().audit());
        var accepted = SiteResultCodecTest.stored(result, codec.encode(result));
        asserter.execute(() -> Panache.withTransaction(() -> companies.insert(parent)
                .chain(() -> sites.insert(result.site())).chain(() -> completions.insert(accepted))));
        asserter.assertThat(() -> Panache.withSession(() -> completions.find(accepted.key())), stored -> {
            assertEquals(result, codec.decode(stored));
            assertEquals(false, stored.snapshot().get("effectivelyOperational"));
        });
    }
}
