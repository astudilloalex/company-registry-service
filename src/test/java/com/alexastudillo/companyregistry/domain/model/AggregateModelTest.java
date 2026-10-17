package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AggregateModelTest {
    @Test
    void versionsStartAtZeroAndRejectNegativeOrOverflowRevisions() {
        assertEquals(0, AggregateVersion.initial().value());
        assertEquals(1, AggregateVersion.initial().next().value());
        assertThrows(IllegalArgumentException.class, () -> new AggregateVersion(-1));
        var maxVersion = new AggregateVersion(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, maxVersion::next);
    }

    @Test
    void companyTransitionMatrixIsExplicit() {
        var allowed = Set.of("DRAFT:ACTIVE", "DRAFT:ARCHIVED", "ACTIVE:INACTIVE",
                "INACTIVE:ACTIVE", "INACTIVE:ARCHIVED");
        for (var from : CompanyStatus.values()) {
            for (var to : CompanyStatus.values()) {
                assertEquals(allowed.contains(from + ":" + to), from.permitsTransitionTo(to));
            }
            assertEquals(from == CompanyStatus.ARCHIVED, from.terminal());
            assertFalse(from.permitsTransitionTo(null));
        }
    }

    @Test
    void siteTransitionMatrixIsExplicit() {
        var allowed = Set.of("DRAFT:ACTIVE", "DRAFT:CLOSED", "ACTIVE:INACTIVE", "ACTIVE:CLOSED",
                "INACTIVE:ACTIVE", "INACTIVE:CLOSED");
        for (var from : SiteStatus.values()) {
            for (var to : SiteStatus.values()) {
                assertEquals(allowed.contains(from + ":" + to), from.permitsTransitionTo(to));
            }
            assertEquals(from == SiteStatus.CLOSED, from.terminal());
            assertFalse(from.permitsTransitionTo(null));
        }
    }

    @Test
    void sitePreparationDoesNotRequireAnActiveCompany() {
        var tenant = UUID.randomUUID();
        var companyId = UUID.randomUUID();
        for (var companyStatus : CompanyStatus.values()) {
            var company = new Company(companyId, tenant, UUID.randomUUID(), companyStatus, AggregateVersion.initial());
            for (var siteStatus : SiteStatus.values()) {
                var site = new Site(UUID.randomUUID(), tenant, companyId, SiteType.HEADQUARTERS,
                        siteStatus, AggregateVersion.initial());
                assertEquals(companyStatus != CompanyStatus.ARCHIVED
                        && (siteStatus == SiteStatus.DRAFT || siteStatus == SiteStatus.INACTIVE),
                        site.canPrepareActivationUnder(company));
                assertEquals(companyStatus == CompanyStatus.ACTIVE && siteStatus == SiteStatus.ACTIVE,
                        site.operationalUnder(company));
            }
        }
    }

    @Test
    void rejectsMissingIdentityAndNeverGrantsOperativityAcrossOwners() {
        var company = new Company(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CompanyStatus.ACTIVE, AggregateVersion.initial());
        var otherTenant = new Site(UUID.randomUUID(), UUID.randomUUID(), company.id(), SiteType.BRANCH,
                SiteStatus.ACTIVE, AggregateVersion.initial());
        var otherCompany = new Site(UUID.randomUUID(), company.tenantId(), UUID.randomUUID(), SiteType.BRANCH,
                SiteStatus.DRAFT, AggregateVersion.initial());
        assertFalse(otherTenant.operationalUnder(company));
        assertFalse(otherCompany.canPrepareActivationUnder(company));
        var tenantId = company.tenantId();
        var partyId = company.partyId();
        var initialVersion = AggregateVersion.initial();
        assertThrows(NullPointerException.class, () -> new Company(null, tenantId, partyId,
                CompanyStatus.DRAFT, initialVersion));
        var siteId = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> new Site(siteId, tenantId, null,
                SiteType.BRANCH, SiteStatus.DRAFT, initialVersion));
    }

    @Test
    void enumsMatchThePersistedVocabulary() {
        assertEquals(Set.of("HEADQUARTERS", "BRANCH", "WAREHOUSE", "OTHER"), names(SiteType.values()));
        assertEquals(Set.of("PHYSICAL", "MAILING"), names(AddressType.values()));
        assertEquals(Set.of("EMAIL", "PHONE", "MOBILE", "WHATSAPP"), names(ContactType.values()));
        assertEquals(Set.of("COMPANY", "COMPANY_SITE"), names(AggregateType.values()));
        assertEquals(Set.of("PENDING", "PUBLISHED", "FAILED"), names(OutboxStatus.values()));
    }

    private static Set<String> names(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).collect(java.util.stream.Collectors.toSet());
    }
}
