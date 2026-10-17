package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.company.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class AcceptedResultCodec implements AcceptedResults {
    private static final ObjectMapper V1 = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build();

    @Override
    public short currentVersion() {
        return 1;
    }

    @Override
    public Map<String, Object> encode(CompanyRegistration.Result result) {
        validate(result);
        return V1.convertValue(result, new TypeReference<>() {
        });
    }

    @Override
    public CompanyRegistration.Result decode(short version, Map<String, Object> document) {
        if (version != 1)
            throw new IllegalStateException("historical-result-version-unavailable");
        try {
            var result = V1.convertValue(document, CompanyRegistration.Result.class);
            validate(result);
            return result;
        } catch (RuntimeException _) {
            // Snapshot values and parser messages must never enter diagnostics.
            throw new IllegalStateException("historical-result-invalid");
        }
    }

    private static void validate(CompanyRegistration.Result result) {
        Objects.requireNonNull(result);
        var row = result.company();
        var company = row.company();
        var audit = row.audit();
        var party = result.party();
        Objects.requireNonNull(row.code());
        Objects.requireNonNull(row.operatingName());
        Objects.requireNonNull(audit);
        Objects.requireNonNull(audit.createdAt());
        Objects.requireNonNull(audit.updatedAt());
        Objects.requireNonNull(audit.createdBy());
        Objects.requireNonNull(audit.updatedBy());
        if (company.status() != com.alexastudillo.companyregistry.domain.model.CompanyStatus.DRAFT
                || company.version().value() != 0
                || !audit.createdAt().equals(audit.updatedAt()) || !audit.createdBy().equals(audit.updatedBy()))
            throw new IllegalArgumentException();
        if (!com.alexastudillo.companyregistry.domain.model.RegistrationPolicy.eligible(party.type(),
                party.recordStatus()) || party.version() < 0)
            throw new IllegalArgumentException();
        Objects.requireNonNull(party.displayName());
        Objects.requireNonNull(party.createdAt());
        Objects.requireNonNull(party.updatedAt());
        Objects.requireNonNull(party.createdBy());
        Objects.requireNonNull(party.updatedBy());
        Objects.requireNonNull(party.legalEntityDetails());
        Objects.requireNonNull(party.legalEntityDetails().legalName());
        Objects.requireNonNull(party.legalEntityDetails().incorporationCountryCode());
        var ids = new java.util.HashSet<java.util.UUID>();
        for (var identifier : party.identifiers()) {
            if (!party.partyId().equals(identifier.partyId()) || !ids.add(identifier.identifierId())
                    || identifier.version() < 0
                    || !java.util.Set.of("PENDING_VERIFICATION", "VERIFIED", "REJECTED", "EXPIRED", "REVOKED")
                            .contains(identifier.status()))
                throw new IllegalArgumentException();
            Objects.requireNonNull(identifier.identifierId());
            Objects.requireNonNull(identifier.identifierSchemeId());
            Objects.requireNonNull(identifier.schemeCode());
            Objects.requireNonNull(identifier.maskedValue());
            Objects.requireNonNull(identifier.createdAt());
            Objects.requireNonNull(identifier.updatedAt());
        }
    }
}
