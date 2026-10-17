package com.alexastudillo.companyregistry.api.company;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompanyRecord;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompanyOperationalResponse(UUID companyId, UUID partyId, String code, String operatingName,
        String description, String logoUrl, String status, long version,
        Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {
    public static CompanyOperationalResponse from(CompanyRecord row) {
        var company = row.company();
        var audit = row.audit();
        return new CompanyOperationalResponse(company.id(), company.partyId(), row.code(), row.operatingName(),
                row.description(), row.logoUrl(),
                company.status().name(), company.version().value(), audit.createdAt(), audit.createdBy(),
                audit.updatedAt(), audit.updatedBy());
    }
}
