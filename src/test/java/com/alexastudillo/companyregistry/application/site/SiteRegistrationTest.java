package com.alexastudillo.companyregistry.application.site;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.RegistryData;
import com.alexastudillo.companyregistry.domain.model.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SiteRegistrationTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID siteId = UUID.fromString("0199e000-0000-7000-8000-000000000001");
    private final OperationContext context = new OperationContext(tenant, " Actor-aB ", UUID.randomUUID().toString());

    @Test
    void candidateKeepsOriginalInputAndInitializesOnlySiteWithOneMicrosecondInstant() {
        var command = new SiteRegistration.Command(context, " Key-aB ", companyId, " sede ", " ágil ", FieldChange.absent());
        var site = SiteRegistration.candidate(command,
                Clock.fixed(Instant.parse("2026-10-17T14:30:00.123456789Z"), ZoneOffset.UTC), () -> siteId);
        assertEquals(siteId, site.site().id());
        assertEquals(tenant, site.site().tenantId());
        assertEquals(companyId, site.site().companyId());
        assertEquals(SiteType.BRANCH, site.site().type());
        assertEquals(SiteStatus.DRAFT, site.site().status());
        assertEquals(0, site.site().version().value());
        assertEquals("SEDE", site.code());
        assertEquals("ÁGIL", site.name());
        assertEquals(Instant.parse("2026-10-17T14:30:00.123456Z"), site.audit().createdAt());
        assertEquals(site.audit().createdAt(), site.audit().updatedAt());
        assertEquals(" Actor-aB ", site.audit().createdBy());
        assertEquals(site.audit().createdBy(), site.audit().updatedBy());
        assertEquals(" Key-aB ", command.idempotencyKey());
        assertEquals(" sede ", command.code());
        assertEquals(" ágil ", command.name());
        assertEquals(FieldChange.absent(), command.siteType());
    }

    @Test
    void projectionChecksOwnershipAndDerivesAllStateCombinationsWithoutChangingRevisions() {
        for (var companyState : CompanyStatus.values()) {
            var company = new Company(companyId, tenant, UUID.randomUUID(), companyState, new AggregateVersion(19));
            for (var siteState : SiteStatus.values()) {
                var row = row(siteState);
                var result = SiteRegistration.Result.under(row, company);
                assertSame(row, result.site());
                assertEquals(companyState == CompanyStatus.ACTIVE && siteState == SiteStatus.ACTIVE,
                        result.effectivelyOperational());
                assertEquals(19, company.version().value());
                assertEquals(7, row.site().version().value());
            }
        }
        var alienTenant = new Company(companyId, UUID.randomUUID(), UUID.randomUUID(), CompanyStatus.ACTIVE, AggregateVersion.initial());
        var alienCompany = new Company(UUID.randomUUID(), tenant, UUID.randomUUID(), CompanyStatus.ACTIVE, AggregateVersion.initial());
        var activeRow = row(SiteStatus.ACTIVE);
        assertThrows(IllegalArgumentException.class, () -> SiteRegistration.Result.under(activeRow, alienTenant));
        assertThrows(IllegalArgumentException.class, () -> SiteRegistration.Result.under(activeRow, alienCompany));
    }

    @Test
    void selectionPreservesFullBoundaryAndCanonicalFiltersWithBoundedLimits() {
        var filters = new SiteSelection.Filters(" ß ", SiteType.HEADQUARTERS, SiteStatus.CLOSED);
        assertEquals("SS", filters.code());
        for (int limit : new int[] { 1, 50, 200 }) assertEquals(limit, new SiteSelection.Request(filters, limit, null).limit());
        for (int limit : new int[] { 0, 201 }) assertThrows(IllegalArgumentException.class,
                () -> new SiteSelection.Request(filters, limit, null));
        var instant = Instant.parse("2026-10-17T14:30:00.123456Z");
        var boundary = new SiteSelection.Boundary(instant, siteId);
        assertEquals(instant, boundary.createdAt());
        assertEquals(siteId, boundary.id());
    }

    private RegistryData.SiteRecord row(SiteStatus status) {
        var now = Instant.parse("2026-10-17T14:30:00.123456Z");
        return new RegistryData.SiteRecord(new Site(siteId, tenant, companyId, SiteType.BRANCH, status,
                new AggregateVersion(7)), "SEDE", "ÁGIL", new RegistryData.Audit(now, "a", now, "a"));
    }
}
