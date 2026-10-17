package com.alexastudillo.companyregistry.application.company;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.Identifiers;
import com.alexastudillo.companyregistry.application.persistence.RegistryData;
import com.alexastudillo.companyregistry.domain.model.*;
import com.alexastudillo.companyregistry.domain.text.OpaqueValue;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Original structured input remains available for historical fingerprint policies. */
public final class CompanyRegistration {
    public static final String OPERATION = "company.create.v1";
    private CompanyRegistration() {}

    public record Command(OperationContext context, String idempotencyKey, UUID partyId, String code,
                          String operatingName, String description, String logoUrl) {
        public Command {
            Objects.requireNonNull(context);
            OpaqueValue.required(idempotencyKey, 128);
        }
        public RegistrationPolicy.Input effective() {
            return RegistrationPolicy.canonical(partyId, code, operatingName, description, logoUrl);
        }
    }

    public record Result(RegistryData.CompanyRecord company, PartyView party) {
        public Result {
            Objects.requireNonNull(company);
            Objects.requireNonNull(party);
            if (!company.company().partyId().equals(party.partyId())) throw new IllegalArgumentException("party-mismatch");
        }
    }

    public static RegistryData.CompanyRecord candidate(Command command, Clock clock, Identifiers identifiers) {
        var input = command.effective();
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var audit = new RegistryData.Audit(now, command.context().actor(), now, command.context().actor());
        var company = new Company(identifiers.next(), command.context().tenantId(), input.partyId(),
                CompanyStatus.DRAFT, AggregateVersion.initial());
        return new RegistryData.CompanyRecord(company, input.code(), input.operatingName(), input.description(), input.logoUrl(), audit);
    }
}
