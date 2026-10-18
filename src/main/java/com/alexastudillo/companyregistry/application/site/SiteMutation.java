package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.RegistryData;
import com.alexastudillo.companyregistry.domain.model.Company;
import com.alexastudillo.companyregistry.domain.model.CompanyStatus;
import com.alexastudillo.companyregistry.domain.model.SiteInformationPatch;
import com.alexastudillo.companyregistry.domain.model.SiteStatus;
import com.alexastudillo.companyregistry.domain.text.OpaqueValue;
import java.util.Objects;
import java.util.UUID;

/** Intent and accepted local result for the four independent HU05 operation namespaces. */
public final class SiteMutation {
    private SiteMutation() {}

    public enum Operation {
        UPDATE("company.site.update.v1", null),
        ACTIVATE("company.site.activate.v1", SiteStatus.ACTIVE),
        DEACTIVATE("company.site.deactivate.v1", SiteStatus.INACTIVE),
        CLOSE("company.site.close.v1", SiteStatus.CLOSED);

        private final String namespace;
        private final SiteStatus target;

        Operation(String namespace, SiteStatus target) {
            this.namespace = namespace;
            this.target = target;
        }

        public String namespace() { return namespace; }
        public SiteStatus target() { return target; }
    }

    public record Command(OperationContext context, UUID companyId, UUID siteId, long expectedVersion,
            String idempotencyKey, Operation operation, SiteInformationPatch patch) {
        public Command {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(companyId, "companyId");
            Objects.requireNonNull(siteId, "siteId");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(patch, "patch");
            if (expectedVersion < 0) throw new IllegalArgumentException("expected-version-invalid");
            if (idempotencyKey != null) OpaqueValue.required(idempotencyKey, 128);
            if (operation != Operation.UPDATE && !patch.equals(SiteInformationPatch.empty())) {
                throw new IllegalArgumentException("site-command-has-information");
            }
        }
    }

    /** Company status is historical verification material, never an additional public field. */
    public record Result(RegistryData.SiteRecord site, CompanyStatus companyStatus) {
        public Result {
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(companyStatus, "companyStatus");
        }

        public boolean effectivelyOperational() {
            return companyStatus == CompanyStatus.ACTIVE && site.site().status() == SiteStatus.ACTIVE;
        }

        public static Result under(RegistryData.SiteRecord site, Company company) {
            Objects.requireNonNull(company, "company");
            if (!site.site().tenantId().equals(company.tenantId()) || !site.site().companyId().equals(company.id())) {
                throw new IllegalArgumentException("site-company-mismatch");
            }
            return new Result(site, company.status());
        }
    }
}
