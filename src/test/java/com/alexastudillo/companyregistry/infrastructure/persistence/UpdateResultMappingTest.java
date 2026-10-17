package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class UpdateResultMappingTest {
    @Inject CompanyRepository companies;
    @Inject CompletionRepository completions;
    @Inject UpdateResultCodec codec;
    @Test @RunOnVertxContext void operationalSnapshotSurvivesRealJsonb(UniAsserter asserter) {
        var original=UpdateResultCodecTest.result();
        var row=new com.alexastudillo.companyregistry.application.persistence.RegistryData.CompanyRecord(
                new com.alexastudillo.companyregistry.domain.model.Company(new UuidV7Generator().next(),original.company().tenantId(),
                        original.company().partyId(), original.company().status(),original.company().version()),original.code(),original.operatingName(),
                original.description(),original.logoUrl(),original.audit());
        var accepted=UpdateResultCodecTest.stored(row,codec.encode(row));
        asserter.execute(()->Panache.withTransaction(()->companies.insert(row).chain(()->completions.insert(accepted))));
        asserter.assertThat(()->Panache.withSession(()->completions.find(accepted.key())),stored->{
            assertEquals(row,codec.decode(stored)); assertTrue(stored.snapshot().containsKey("description"));
            assertNull(stored.snapshot().get("description"));
        });
    }
}
