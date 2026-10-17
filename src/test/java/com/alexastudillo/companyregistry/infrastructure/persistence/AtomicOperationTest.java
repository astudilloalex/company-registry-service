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
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class AtomicOperationTest {
    @Inject AtomicOperation atomic;
    @Inject CompanyRepository companies;
    @Inject CompletionRepository completions;
    @Inject OutboxRepository events;
    @Inject UuidV7Generator ids;

    record Fixture(CompanyRecord company, CompletionRecord completion, OutboxRecord event) {}
    private Fixture fixture(boolean invalidEvent) {
        var tenant = UUID.randomUUID(); var id = ids.next(); var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var audit = new Audit(now, "Actor-aB", now, "Actor-aB");
        var company = new CompanyRecord(new Company(id, tenant, UUID.randomUUID(), CompanyStatus.DRAFT, AggregateVersion.initial()), "ATOMIC", "COMPANY", null, null, audit);
        var completion = new CompletionRecord(new CompletionKey(tenant, "company.create", "Key-aB"), "a".repeat(64), id, null, null, (short)201, (short)1, Map.of("id", id.toString()), now, "Actor-aB");
        var event = new OutboxRecord(ids.next(), tenant, AggregateType.COMPANY, id, 0, "company.created.v1", (short)1, Map.of("id", id.toString()),
                now, "Process-aB", null, OutboxStatus.PENDING, invalidEvent ? -1 : 0, null, null, null, null, null, audit, 0);
        return new Fixture(company, completion, event);
    }
    private Uni<OutboxRecord> write(Fixture f) {
        return companies.insert(f.company()).chain(() -> completions.insert(f.completion())).chain(() -> events.insert(f.event()));
    }
    private void absent(UniAsserter a, Fixture f) {
        var company = f.company().company();
        a.assertNull(() -> atomic.read(() -> companies.find(company.tenantId(), company.id())));
        a.assertNull(() -> atomic.read(() -> completions.find(f.completion().key())));
        a.assertNull(() -> atomic.read(() -> events.find(company.tenantId(), f.event().id())));
    }

    @Test @RunOnVertxContext
    void commitsAllThreeAndClassifiesDuplicate(UniAsserter a) {
        var f = fixture(false); var company = f.company().company();
        a.assertEquals(() -> atomic.execute(() -> write(f)), f.event());
        a.assertEquals(() -> atomic.read(() -> companies.find(company.tenantId(), company.id())), f.company());
        a.assertEquals(() -> atomic.read(() -> completions.find(f.completion().key())), f.completion());
        a.assertEquals(() -> atomic.read(() -> events.find(company.tenantId(), f.event().id())), f.event());
        a.assertFailedWith(() -> atomic.execute(() -> companies.insert(f.company())), failure ->
                assertEquals(PersistenceFailure.Kind.UNEXPECTED, assertInstanceOf(PersistenceFailure.class, failure).kind()));
    }

    @Test @RunOnVertxContext
    void businessFailureRollsBackEverythingWithoutErasingFailure(UniAsserter a) {
        var f = fixture(false); var original = new IllegalStateException("business-fixture-failure");
        a.assertFailedWith(() -> atomic.execute(() -> write(f).chain(() -> Uni.createFrom().failure(original))),
                failure -> assertSame(original, failure));
        absent(a, f);
    }

    @Test @RunOnVertxContext
    void databaseFailureRollsBackAggregateAndCompletionAndIsNeutral(UniAsserter a) {
        var f = fixture(true);
        a.assertFailedWith(() -> atomic.execute(() -> write(f)), failure -> {
            var neutral = assertInstanceOf(PersistenceFailure.class, failure);
            assertEquals(PersistenceFailure.Kind.INVALID_DATA, neutral.kind());
            assertEquals("persistence-invalid_data", neutral.getMessage());
        });
        absent(a, f);
    }
}
