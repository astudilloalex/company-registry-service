package com.alexastudillo.companyregistry.application.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.RegistryData;
import com.alexastudillo.companyregistry.domain.model.AggregateVersion;
import com.alexastudillo.companyregistry.domain.model.Company;
import com.alexastudillo.companyregistry.domain.model.CompanyStatus;
import com.alexastudillo.companyregistry.domain.model.FieldChange;
import com.alexastudillo.companyregistry.domain.model.Site;
import com.alexastudillo.companyregistry.domain.model.SiteInformationPatch;
import com.alexastudillo.companyregistry.domain.model.SiteStatus;
import com.alexastudillo.companyregistry.domain.model.SiteType;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SiteMutationTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID siteId = UUID.randomUUID();
    private final OperationContext context = new OperationContext(tenant, " Actor-aB ", UUID.randomUUID().toString());

    @Test
    void namespacesAndTargetsAreExplicitAndIndependent() {
        assertEquals(4, Arrays.stream(SiteMutation.Operation.values()).map(SiteMutation.Operation::namespace).distinct().count());
        assertEquals("company.site.update.v1", SiteMutation.Operation.UPDATE.namespace());
        assertEquals("company.site.activate.v1", SiteMutation.Operation.ACTIVATE.namespace());
        assertEquals("company.site.deactivate.v1", SiteMutation.Operation.DEACTIVATE.namespace());
        assertEquals("company.site.close.v1", SiteMutation.Operation.CLOSE.namespace());
        assertEquals(SiteStatus.ACTIVE, SiteMutation.Operation.ACTIVATE.target());
        assertEquals(SiteStatus.INACTIVE, SiteMutation.Operation.DEACTIVATE.target());
        assertEquals(SiteStatus.CLOSED, SiteMutation.Operation.CLOSE.target());
    }

    @Test
    void preservesOptionalOpaqueKeyRawIntentAndExpectedSiteVersion() {
        var patch = new SiteInformationPatch(FieldChange.absent(), FieldChange.present(" ágil "), FieldChange.absent());
        var command = new SiteMutation.Command(context, companyId, siteId, 9, " Key-aB ", SiteMutation.Operation.UPDATE, patch);
        assertEquals(" ágil ", command.patch().name().value());
        assertEquals(" Key-aB ", command.idempotencyKey());
        assertEquals(9, command.expectedVersion());
        var noKey = new SiteMutation.Command(context, companyId, siteId, 9, null, SiteMutation.Operation.UPDATE, patch);
        assertEquals(null, noKey.idempotencyKey());
        var negative = assertThrows(IllegalArgumentException.class, () -> new SiteMutation.Command(context,
                companyId, siteId, -1, null, SiteMutation.Operation.UPDATE, patch));
        assertEquals("expected-version-invalid", negative.getMessage());
        for (var operation : new SiteMutation.Operation[] {SiteMutation.Operation.ACTIVATE,
                SiteMutation.Operation.DEACTIVATE, SiteMutation.Operation.CLOSE}) {
            var invalid = assertThrows(IllegalArgumentException.class, () -> new SiteMutation.Command(context,
                    companyId, siteId, 9, null, operation, patch));
            assertEquals("site-command-has-information", invalid.getMessage());
            var valid = new SiteMutation.Command(context, companyId, siteId, 9, null, operation, SiteInformationPatch.empty());
            assertEquals(SiteInformationPatch.empty(), valid.patch());
        }
    }

    @Test
    void resultRetainsOriginalParentStateAndQualifiedOwnership() {
        var now = Instant.parse("2026-10-18T14:30:00.123456Z");
        for (var parentStatus : CompanyStatus.values()) {
            var parent = new Company(companyId, tenant, UUID.randomUUID(), parentStatus, new AggregateVersion(19));
            for (var siteStatus : SiteStatus.values()) {
                var row = new RegistryData.SiteRecord(new Site(siteId, tenant, companyId, SiteType.BRANCH,
                        siteStatus, new AggregateVersion(7)), "CODE", "NAME", new RegistryData.Audit(now, "a", now, "a"));
                var result = SiteMutation.Result.under(row, parent);
                assertEquals(parentStatus, result.companyStatus());
                assertEquals(parentStatus == CompanyStatus.ACTIVE && siteStatus == SiteStatus.ACTIVE,
                        result.effectivelyOperational());
                assertEquals(19, parent.version().value());
                assertEquals(row, result.site());
                var alien = new Company(companyId, UUID.randomUUID(), parent.partyId(), parentStatus, parent.version());
                var failure = assertThrows(IllegalArgumentException.class, () -> SiteMutation.Result.under(row, alien));
                assertEquals("site-company-mismatch", failure.getMessage());
                var other = new Company(UUID.randomUUID(), tenant, parent.partyId(), parentStatus, parent.version());
                assertNotEquals(companyId, other.id());
                assertThrows(IllegalArgumentException.class, () -> SiteMutation.Result.under(row, other));
            }
        }
    }
}
