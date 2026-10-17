package com.alexastudillo.companyregistry.application.company;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Safe provider read model. Legal identity remains authoritative in Party Registry. */
public record PartyView(UUID partyId, String type, String displayName, String recordStatus, long version,
                        Instant createdAt, Instant updatedAt, String createdBy, String updatedBy,
                        LegalEntity legalEntityDetails, List<Identifier> identifiers) {
    public PartyView { identifiers = List.copyOf(identifiers); }

    public record LegalEntity(String legalName, String tradeName, String legalFormCode, String incorporationCountryCode,
                              LocalDate incorporatedOn, LocalDate dissolvedOn) {}
    public record Identifier(UUID identifierId, UUID partyId, UUID identifierSchemeId, String schemeCode,
                             String maskedValue, String status, boolean isPrimary, String issuerCode,
                             LocalDate issuedOn, LocalDate expiresOn, Instant verifiedAt, String verifiedBy,
                             long version, Instant createdAt, Instant updatedAt) {}
}
