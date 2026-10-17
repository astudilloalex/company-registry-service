package com.alexastudillo.companyregistry.application.verification;

import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.domain.model.*;
import io.smallrye.mutiny.Uni;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Verification-only orchestration of six mappings and atomic rollback; not a registration workflow. */
public final class PersistenceVerification {
    public record Result(UUID companyId, UUID siteId, UUID eventId, boolean rolledBack) {}
    private final AtomicOperation atomic;
    private final CompanyRepository companies;
    private final SiteRepository sites;
    private final CompletionRepository completions;
    private final OutboxRepository events;
    private final Identifiers ids;
    public PersistenceVerification(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites,
                                   CompletionRepository completions, OutboxRepository events, Identifiers ids) {
        this.atomic = atomic; this.companies = companies; this.sites = sites;
        this.completions = completions; this.events = events; this.ids = ids;
    }
    public Uni<Result> verify(boolean rollback) {
        var tenant = ids.next(); var companyId = ids.next(); var siteId = ids.next();
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var audit = new Audit(now, "Actor-aB", now, "Actor-aB");
        var company = new CompanyRecord(new Company(companyId, tenant, ids.next(), CompanyStatus.DRAFT, AggregateVersion.initial()),
                "VERIFY", "COMPAÑÍA  ÁGIL", "DESCRIPCIÓN", "https://host/LogoAb.png", audit);
        var site = new SiteRecord(new Site(siteId, tenant, companyId, SiteType.HEADQUARTERS, SiteStatus.ACTIVE, AggregateVersion.initial()), "LEGAL", "SEDE", audit);
        var address = new AddressRecord(ids.next(), siteId, AddressType.PHYSICAL, "EC", "EC-P", "DIRECCIÓN", "PISO DOS", "12A", "170101",
                new BigDecimal("-0.123456"), new BigDecimal("78.123456"), false, audit, now, "Delete-aB");
        var contact = new ContactRecord(ids.next(), siteId, ContactType.EMAIL, "Mi.Usuario@example.com", "CORREO", false, audit, now, "Delete-aB");
        Map<String, Object> snapshot = new LinkedHashMap<>(Map.of("opaque", "Key-aB", "nested", Map.of("url", "https://host/LogoAb.png", "number", 12)));
        snapshot.put("explicitNull", null);
        var completion = new CompletionRecord(new CompletionKey(tenant, "verification", "Key-aB"), "a".repeat(64), companyId, siteId,
                0L, (short)201, (short)2, snapshot, now, "Actor-aB");
        var event = new OutboxRecord(ids.next(), tenant, AggregateType.COMPANY_SITE, siteId, 0, "company.site.created.v1", (short)2,
                snapshot, now, "Process-aB", "Cause-aB", OutboxStatus.PENDING, 1, now.plusSeconds(30), now, null, "retry-code", "Diagnostic-aB", audit, 0);
        return atomic.execute(() -> companies.insert(company).chain(() -> sites.insert(site))
                        .chain(() -> sites.insertAddress(tenant, companyId, address)).chain(() -> sites.insertContact(tenant, companyId, contact))
                        .chain(() -> completions.insert(completion)).chain(() -> events.insert(event))
                        .chain(saved -> rollback ? Uni.createFrom().failure(new PlannedRollback()) : Uni.createFrom().item(saved)))
                .onFailure(PlannedRollback.class).recoverWithNull()
                .chain(() -> atomic.read(() -> companies.find(tenant, companyId).invoke(actual -> match(rollback ? null : company, actual))
                        .chain(() -> sites.find(tenant, companyId, siteId)).invoke(actual -> match(rollback ? null : site, actual))
                        .chain(() -> sites.addresses(tenant, companyId, siteId)).invoke(actual -> match(rollback ? List.of() : List.of(address), actual))
                        .chain(() -> sites.contacts(tenant, companyId, siteId)).invoke(actual -> match(rollback ? List.of() : List.of(contact), actual))
                        .chain(() -> completions.find(completion.key())).invoke(actual -> match(rollback ? null : completion, actual))
                        .chain(() -> events.find(tenant, event.id())).invoke(actual -> match(rollback ? null : event, actual))
                        .replaceWith(new Result(companyId, siteId, event.id(), rollback))));
    }
    private static void match(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new IllegalStateException("verification-round-trip-mismatch");
    }
    private static final class PlannedRollback extends RuntimeException {}
}
