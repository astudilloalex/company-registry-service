package com.alexastudillo.companyregistry.infrastructure.outbox;

import com.alexastudillo.companyregistry.application.site.*;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.*;
import jakarta.inject.Inject;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class SiteCreatedAtomicTest {
    @Inject
    AtomicOperation atomic;
    @Inject
    CompanyRepository companies;
    @Inject SiteRepository sites;
    @Inject
    CompletionRepository completions;
    @Inject
    OutboxRepository outbox;
    @Inject
    UuidV7Generator ids;

    private OutboxConfiguration configuration(String mode) {
        return new io.smallrye.config.SmallRyeConfigBuilder()
                .withConverter(Duration.class, 100, new io.quarkus.runtime.configuration.DurationConverter())
                .withMapping(OutboxConfiguration.class).withDefaultValue("company-registry.outbox.mode", mode).build()
                .getConfigMapping(OutboxConfiguration.class);
    }

    @Test
    @RunOnVertxContext
    void storesExactlyOnePendingOrNoEventWithinTheOuterTransaction(UniAsserter asserter) {
        for (var mode : List.of("disabled", "stored-only", "published")) {
            var context = new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString());
            var site = SiteRegistration.candidate(
                    new SiteRegistration.Command(context, "key", UUID.randomUUID(), mode, "name", com.alexastudillo.companyregistry.domain.model.FieldChange.absent()),
                    Clock.systemUTC(), ids);
            var company = parent(site);
            var event = SiteCreated.candidate(ids.next(), site, context);
            var row = SiteCreatedOutbox.row(event);
            var completion = new CompletionRecord(new CompletionKey(context.tenantId(), "event-test", mode),
                    "a".repeat(64), site.site().companyId(), site.site().id(), null, (short) 201, (short) 1,
                    Map.of("accepted", true), site.audit().createdAt(), context.actor());
            var policy = new OutboxPolicy(configuration(mode), outbox);
            asserter.execute(() -> atomic.execute(() -> companies.insert(company)
                    .chain(() -> sites.insert(site))
                    .chain(() -> completions.insert(completion)).chain(() -> policy.store(row))));
            asserter.assertEquals(() -> atomic.read(() -> companies.find(context.tenantId(), site.site().companyId())),
                    company);
            asserter.assertEquals(() -> atomic.read(() -> completions.find(completion.key())), completion);
            if (mode.equals("disabled"))
                asserter.assertNull(() -> atomic.read(() -> outbox.find(context.tenantId(), event.eventId())));
            else
                asserter.assertEquals(() -> atomic.read(() -> outbox.find(context.tenantId(), event.eventId())), row);
        }
    }

    @Test
    @RunOnVertxContext
    void eventFailureRollsBackSiteCompanyAndCompletion(UniAsserter asserter) {
        var context = new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString());
        var site = SiteRegistration.candidate(
                new SiteRegistration.Command(context, "key", UUID.randomUUID(), "rollback", "name", com.alexastudillo.companyregistry.domain.model.FieldChange.absent()),
                Clock.systemUTC(), ids);
        var company = parent(site);
        var good = SiteCreated.candidate(ids.next(), site, context);
        var invalid = new SiteCreated(good.eventId(), good.tenantId(), good.aggregateType(), good.aggregateId(), 0,
                good.eventType(), (short) 0, good.occurredAt(), good.correlationId(), null, good.payload());
        var event = SiteCreatedOutbox.row(invalid);
        var policy = new OutboxPolicy(configuration("stored-only"), outbox);
        var completion = new CompletionRecord(new CompletionKey(context.tenantId(), "event-test", "rollback"),
                "a".repeat(64), site.site().companyId(), site.site().id(), null, (short) 201, (short) 1, Map.of("accepted", true),
                site.audit().createdAt(), context.actor());
        asserter.assertFailedWith(() -> atomic.execute(() -> companies.insert(company)
                    .chain(() -> sites.insert(site))
                .chain(() -> completions.insert(completion)).chain(() -> policy.store(event))),
                PersistenceFailure.class);
        asserter.assertNull(() -> atomic.read(() -> companies.find(context.tenantId(), site.site().companyId())));
        asserter.assertNull(() -> atomic.read(() -> sites.find(context.tenantId(), site.site().companyId(), site.site().id())));
        asserter.assertNull(() -> atomic.read(() -> completions.find(completion.key())));
        asserter.assertNull(() -> atomic.read(() -> outbox.find(context.tenantId(), event.id())));
    }

    private CompanyRecord parent(SiteRecord row) {
        return new CompanyRecord(new com.alexastudillo.companyregistry.domain.model.Company(row.site().companyId(), row.site().tenantId(),
                UUID.randomUUID(), com.alexastudillo.companyregistry.domain.model.CompanyStatus.DRAFT,
                com.alexastudillo.companyregistry.domain.model.AggregateVersion.initial()),
                "PARENT", "PARENT", null, null, row.audit());
    }
}
