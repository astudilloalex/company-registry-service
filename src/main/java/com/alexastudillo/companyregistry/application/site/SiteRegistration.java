package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.Identifiers;
import com.alexastudillo.companyregistry.application.persistence.RegistryData;
import com.alexastudillo.companyregistry.domain.model.*;
import com.alexastudillo.companyregistry.domain.text.OpaqueValue;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Original input is retained for the policy selected by an existing acceptance. */
public final class SiteRegistration {
    public static final String OPERATION = "company.site.create.v1";
    private SiteRegistration() {}

    public record Command(OperationContext context, String idempotencyKey, UUID companyId,
                          String code, String name, FieldChange siteType) {
        public Command {
            Objects.requireNonNull(context, "context");
            OpaqueValue.required(idempotencyKey, 128);
            Objects.requireNonNull(companyId, "companyId");
            Objects.requireNonNull(siteType, "siteType");
        }

        public SiteRegistrationPolicy.Input effective() {
            return SiteRegistrationPolicy.canonical(code, name, siteType);
        }
    }

    public record Result(RegistryData.SiteRecord site, boolean effectivelyOperational) {
        public Result {
            Objects.requireNonNull(site, "site");
        }

        /** Both aggregates must come from the same qualified local read. */
        public static Result under(RegistryData.SiteRecord site, Company company) {
            Objects.requireNonNull(company, "company");
            if (!site.site().tenantId().equals(company.tenantId())
                    || !site.site().companyId().equals(company.id())) {
                throw new IllegalArgumentException("site-company-mismatch");
            }
            return new Result(site, site.site().operationalUnder(company));
        }
    }

    public static RegistryData.SiteRecord candidate(Command command, Clock clock, Identifiers identifiers) {
        var input = command.effective();
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var actor = command.context().actor();
        var site = new Site(identifiers.next(), command.context().tenantId(), command.companyId(), input.siteType(),
                SiteStatus.DRAFT, AggregateVersion.initial());
        return new RegistryData.SiteRecord(site, input.code(), input.name(),
                new RegistryData.Audit(now, actor, now, actor));
    }
}
