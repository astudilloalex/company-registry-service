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
class LifecycleResultMappingTest {
    @Inject CompanyRepository companies;
    @Inject CompletionRepository completions;
    @Inject LifecycleResultCodec codec;
    @Test @RunOnVertxContext void operationalSnapshotSurvivesRealJsonb(UniAsserter asserter) {
        for (var operation : com.alexastudillo.companyregistry.application.company.CompanyLifecycle.Operation.values()) {
        var original=LifecycleResultCodecTest.result();
        var row=new com.alexastudillo.companyregistry.application.persistence.RegistryData.CompanyRecord(
                new com.alexastudillo.companyregistry.domain.model.Company(new UuidV7Generator().next(),original.company().tenantId(),
                        original.company().partyId(), operation.target(),original.company().version()),original.code(),original.operatingName(),
                original.description(),original.logoUrl(),original.audit());
        var accepted=LifecycleResultCodecTest.stored(row,codec.encode(row));
        accepted = new com.alexastudillo.companyregistry.application.persistence.RegistryData.CompletionRecord(
            new com.alexastudillo.companyregistry.application.persistence.RegistryData.CompletionKey(row.company().tenantId(), operation.namespace(), "key"),
            accepted.requestHash(), accepted.companyId(), null, accepted.expectedVersion(), accepted.resultStatus(), accepted.snapshotVersion(),
            accepted.snapshot(), accepted.createdAt(), accepted.createdBy(), accepted.fingerprintVersion(), accepted.fingerprintKeyId());
        final var completion = accepted;
        asserter.execute(()->Panache.withTransaction(()->companies.insert(row).chain(()->completions.insert(completion))));
        asserter.assertThat(()->Panache.withSession(()->completions.find(completion.key())),stored->{
            assertEquals(row,codec.decode(stored)); assertTrue(stored.snapshot().containsKey("description"));
            assertNull(stored.snapshot().get("description"));
        });
        }
    }
}
