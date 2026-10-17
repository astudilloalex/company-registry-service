package com.alexastudillo.companyregistry.infrastructure.persistence;

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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class TechnicalMappingTest {
    @Inject CompanyRepository companies;
    @Inject SiteRepository sites;
    @Inject CompletionRepository completions;
    @Inject OutboxRepository events;
    @Inject UuidV7Generator ids;

    @Test @RunOnVertxContext
    void compositeKeySnapshotAndEventMetadataRoundTrip(UniAsserter asserter) {
        var tenant = UUID.randomUUID(); var id = ids.next(); var siteId = ids.next();
        var now = Instant.parse("2026-10-15T12:30:45.123456Z"); var audit = new Audit(now, "Actor-aB", now, "Actor-aB");
        var company = new CompanyRecord(new Company(id, tenant, UUID.randomUUID(), CompanyStatus.DRAFT, AggregateVersion.initial()), "TECH", "COMPANY", null, null, audit);
        var site = new SiteRecord(new Site(siteId, tenant, id, SiteType.BRANCH, SiteStatus.DRAFT, AggregateVersion.initial()), "SITE", "SEDE", audit);
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>(Map.of("url", "https://host/LogoAb.png", "nested", Map.of("key", "Key-aB", "value", 12)));
        snapshot.put("explicitNull", null);
        snapshot.put("party", Map.of("identifiers", java.util.List.of(Map.of("isPrimary", true, "maskedValue", "*********0001", "version", 2)),
                "incorporatedOn", "2020-01-15", "createdAt", now.toString()));
        var completion = new CompletionRecord(new CompletionKey(tenant, "company.create", "Key-aB"), "a".repeat(64), id, null, null, (short)201, (short)1, snapshot, now, "Actor-aB");
        var creation = new CompletionRecord(new CompletionKey(tenant, "company.create.v1", "Key-aB"), "c".repeat(64), id, null, null,
                (short)201, (short)1, snapshot, now, "Actor-aB", (short)1, "K1");
        var siteCompletion = new CompletionRecord(new CompletionKey(tenant, "site.update", "Key-aB"), "b".repeat(64), id, siteId, 4L, (short)200, (short)2, snapshot, now, "Actor-aB");
        var event = new OutboxRecord(ids.next(), tenant, AggregateType.COMPANY_SITE, siteId, 4, "company.site.updated.v1", (short)2,
                snapshot, now, "Process-aB", "Cause-aB", OutboxStatus.FAILED, 3, now.plusSeconds(30), now, null,
                "provider-unavailable", "Diagnostic-aB", audit, 0);
        asserter.execute(() -> Panache.withTransaction(() -> companies.insert(company).chain(() -> sites.insert(site))
                .chain(() -> completions.insert(completion)).chain(() -> completions.insert(creation))
                .chain(() -> completions.insert(siteCompletion)).chain(() -> events.insert(event))));
        asserter.assertEquals(() -> Panache.withSession(() -> completions.find(completion.key())), completion);
        asserter.assertEquals(() -> Panache.withSession(() -> completions.find(creation.key())), creation);
        asserter.assertEquals(() -> Panache.withSession(() -> completions.find(siteCompletion.key())), siteCompletion);
        asserter.assertEquals(() -> Panache.withSession(() -> events.find(tenant, event.id())), event);
        asserter.assertNull(() -> Panache.withSession(() -> events.find(UUID.randomUUID(), event.id())));
        asserter.assertNull(() -> Panache.withSession(() -> completions.find(new CompletionKey(UUID.randomUUID(), "company.create", "Key-aB"))));
        asserter.assertNull(() -> Panache.withSession(() -> completions.find(new CompletionKey(tenant, "other", "Key-aB"))));
        asserter.assertNull(() -> Panache.withSession(() -> completions.find(new CompletionKey(tenant, "company.create", "Key-ab"))));
    }
}
