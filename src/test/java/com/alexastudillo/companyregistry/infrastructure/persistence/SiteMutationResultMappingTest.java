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
class SiteMutationResultMappingTest {
    @Inject CompanyRepository companies;
    @Inject SiteRepository sites;
    @Inject CompletionRepository completions;
    @Inject SiteMutationResultCodec codec;
    @Test @RunOnVertxContext void originalSiteSnapshotSurvivesRealJsonb(UniAsserter asserter) {
        for (var operation : com.alexastudillo.companyregistry.application.site.SiteMutation.Operation.values()) {
            for (boolean noop : new boolean[] {false, true}) {
                if (noop && operation != com.alexastudillo.companyregistry.application.site.SiteMutation.Operation.UPDATE) continue;
                var tenant = UUID.randomUUID();
                var companyId = new UuidV7Generator().next();
                var original = SiteMutationResultCodecTest.result(tenant, companyId);
                var status = operation == com.alexastudillo.companyregistry.application.site.SiteMutation.Operation.UPDATE ? SiteStatus.DRAFT : operation.target();
                var site = original.site().site();
                var row = new SiteRecord(new Site(site.id(), site.tenantId(), site.companyId(), site.type(), status, site.version()),
                        original.site().code(), original.site().name(), original.site().audit());
                var result = new com.alexastudillo.companyregistry.application.site.SiteMutation.Result(row, CompanyStatus.ACTIVE);
                var parent = new CompanyRecord(new Company(companyId, tenant, UUID.randomUUID(), CompanyStatus.DRAFT,
                        AggregateVersion.initial()), UUID.randomUUID().toString().toUpperCase(), "COMPANY", null, null, row.audit());
                var accepted = new CompletionRecord(new CompletionKey(tenant, operation.namespace(), "jsonb"), "a".repeat(64),
                        companyId, site.id(), noop ? 8L : 7L, (short)200, (short)1, codec.encode(result),
                        noop ? row.audit().updatedAt().plusSeconds(60) : row.audit().updatedAt(),
                        noop ? "OtherActor" : row.audit().updatedBy(), (short)1, "K1");
                asserter.execute(() -> Panache.withTransaction(() -> companies.insert(parent)
                        .chain(() -> sites.insert(row)).chain(() -> completions.insert(accepted))));
                asserter.assertThat(() -> Panache.withSession(() -> completions.find(accepted.key())), stored -> {
                    assertEquals(result, codec.decode(stored));
                    assertEquals(result.effectivelyOperational(), stored.snapshot().get("effectivelyOperational"));
                });
            }
        }
    }
}
