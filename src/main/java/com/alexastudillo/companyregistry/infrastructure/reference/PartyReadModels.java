package com.alexastudillo.companyregistry.infrastructure.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PartyReadModels {
    private PartyReadModels() {}
    @JsonIgnoreProperties(ignoreUnknown = true) @RegisterForReflection
    public record Detail(UUID partyId, String type, String displayName, String recordStatus, Long version,
                         Instant createdAt, Instant updatedAt, String createdBy, String updatedBy, Legal legalEntityDetails) {}
    @JsonIgnoreProperties(ignoreUnknown = true) @RegisterForReflection
    public record Legal(String legalName, String tradeName, String legalFormCode, String incorporationCountryCode,
                        LocalDate incorporatedOn, LocalDate dissolvedOn) {}
    @JsonIgnoreProperties(ignoreUnknown = true) @RegisterForReflection
    public record Identifier(UUID identifierId, UUID partyId, UUID identifierSchemeId, String schemeCode, String maskedValue,
                             String status, Boolean isPrimary, String issuerCode, LocalDate issuedOn, LocalDate expiresOn,
                             Instant verifiedAt, String verifiedBy, Long version, Instant createdAt, Instant updatedAt) {}
    @JsonIgnoreProperties(ignoreUnknown = true) @RegisterForReflection
    public record Page(Integer status, String code, List<Identifier> data, String nextCursor, Integer numberOfElements) {}
}
