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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class ChildMappingTest {
    @Inject CompanyRepository companies;
    @Inject SiteRepository sites;
    @Inject UuidV7Generator ids;

    @Test @RunOnVertxContext
    void roundTripHistoryFormatsAndScopedReadWrite(UniAsserter asserter) {
        var tenant = UUID.randomUUID(); var id = ids.next(); var siteId = ids.next();
        var now = Instant.parse("2026-10-15T12:30:45.123456Z"); var audit = new Audit(now, "Actor-aB", now, "Actor-aB");
        var company = new CompanyRecord(new Company(id, tenant, UUID.randomUUID(), CompanyStatus.DRAFT, AggregateVersion.initial()), "CHILD", "COMPANY", null, null, audit);
        var site = new SiteRecord(new Site(siteId, tenant, id, SiteType.BRANCH, SiteStatus.DRAFT, AggregateVersion.initial()), "SITE", "SEDE", audit);
        var address = new AddressRecord(ids.next(), siteId, AddressType.PHYSICAL, "EC", "EC-P", "DIRECCIÓN", "PISO DOS", "12A", "170101",
                new BigDecimal("-0.123456"), new BigDecimal("78.123456"), false, audit, now, "Delete-aB");
        var email = new ContactRecord(ids.next(), siteId, ContactType.EMAIL, "Mi.Usuario@example.com", "CORREO", false, audit, now, "Delete-aB");
        var phone = new ContactRecord(ids.next(), siteId, ContactType.PHONE, "+593999999999", "TELÉFONO", true, audit, null, null);
        asserter.execute(() -> Panache.withTransaction(() -> companies.insert(company).chain(() -> sites.insert(site))
                .chain(() -> sites.insertAddress(tenant, id, address)).chain(() -> sites.insertContact(tenant, id, email))
                .chain(() -> sites.insertContact(tenant, id, phone))));
        asserter.assertEquals(() -> Panache.withSession(() -> sites.addresses(tenant, id, siteId)), List.of(address));
        asserter.assertThat(() -> Panache.withSession(() -> sites.contacts(tenant, id, siteId)), rows -> {
            assertEquals(2, rows.size()); assertTrue(rows.contains(email)); assertTrue(rows.contains(phone));
        });
        for (var owner : new UUID[][]{{UUID.randomUUID(), id}, {tenant, UUID.randomUUID()}}) {
            asserter.assertEquals(() -> Panache.withSession(() -> sites.addresses(owner[0], owner[1], siteId)), List.of());
            asserter.assertEquals(() -> Panache.withSession(() -> sites.contacts(owner[0], owner[1], siteId)), List.of());
            asserter.assertFailedWith(() -> Panache.withTransaction(() -> sites.insertAddress(owner[0], owner[1], address)),
                    failure -> assertEquals(PersistenceFailure.Kind.INVALID_REFERENCE, assertInstanceOf(PersistenceFailure.class, failure).kind()));
            asserter.assertFailedWith(() -> Panache.withTransaction(() -> sites.insertContact(owner[0], owner[1], email)),
                    failure -> assertEquals(PersistenceFailure.Kind.INVALID_REFERENCE, assertInstanceOf(PersistenceFailure.class, failure).kind()));
        }
    }
}
