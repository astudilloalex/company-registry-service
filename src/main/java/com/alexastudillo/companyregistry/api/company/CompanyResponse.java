package com.alexastudillo.companyregistry.api.company;

import com.alexastudillo.companyregistry.application.company.CompanyRegistration;
import com.alexastudillo.companyregistry.application.company.PartyView;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompanyResponse(UUID companyId, UUID partyId, String code, String operatingName,
        String description, String logoUrl, String status, long version,
        Instant createdAt, String createdBy, Instant updatedAt, String updatedBy, Party party) {
    public static CompanyResponse from(CompanyRegistration.Result result) {
        var row = result.company();
        var company = row.company();
        var audit = row.audit();
        return new CompanyResponse(company.id(), company.partyId(), row.code(), row.operatingName(), row.description(),
                row.logoUrl(),
                company.status().name(), company.version().value(), audit.createdAt(), audit.createdBy(),
                audit.updatedAt(), audit.updatedBy(), Party.from(result.party()));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Party(UUID partyId, String type, String displayName, String recordStatus, long version,
            Instant createdAt, Instant updatedAt, String createdBy, String updatedBy,
            LegalEntity legalEntityDetails, List<Identifier> identifiers) {
        static Party from(PartyView view) {
            var legal = view.legalEntityDetails();
            return new Party(view.partyId(), view.type(), view.displayName(), view.recordStatus(), view.version(),
                    view.createdAt(), view.updatedAt(),
                    view.createdBy(), view.updatedBy(),
                    new LegalEntity(legal.legalName(), legal.tradeName(), legal.legalFormCode(),
                            legal.incorporationCountryCode(), legal.incorporatedOn(), legal.dissolvedOn()),
                    view.identifiers().stream().map(Identifier::from).toList());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LegalEntity(String legalName, String tradeName, String legalFormCode, String incorporationCountryCode,
            LocalDate incorporatedOn, LocalDate dissolvedOn) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Identifier(UUID identifierId, UUID partyId, UUID identifierSchemeId, String schemeCode,
            String maskedValue, String status, boolean isPrimary, String issuerCode,
            LocalDate issuedOn, LocalDate expiresOn, Instant verifiedAt, String verifiedBy,
            long version, Instant createdAt, Instant updatedAt) {
        static Identifier from(PartyView.Identifier value) {
            return new Identifier(value.identifierId(), value.partyId(), value.identifierSchemeId(), value.schemeCode(),
                    value.maskedValue(),
                    value.status(), value.isPrimary(), value.issuerCode(), value.issuedOn(), value.expiresOn(),
                    value.verifiedAt(), value.verifiedBy(),
                    value.version(), value.createdAt(), value.updatedAt());
        }
    }
}
