package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompanyRecord;
import com.alexastudillo.companyregistry.domain.model.*;
import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "companies")
public class CompanyEntity extends AuditedRow {
    @Id
    public UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false)
    public UUID tenantId;
    @Column(name = "party_id", nullable = false, updatable = false)
    public UUID partyId;
    @Column(nullable = false, length = 64)
    public String code;
    @Column(name = "operating_name", nullable = false, length = 200)
    public String operatingName;
    @Column(length = 500)
    public String description;
    @Column(name = "logo_url", length = 1000)
    public String logoUrl;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "company_record_status")
    public CompanyStatus status;
    @Version
    @Column(nullable = false)
    public long version;

    public static CompanyEntity from(CompanyRecord value) {
        var row = new CompanyEntity();
        var company = value.company();
        row.id = company.id();
        row.tenantId = company.tenantId();
        row.partyId = company.partyId();
        row.status = company.status();
        row.version = company.version().value();
        row.code = value.code();
        row.operatingName = value.operatingName();
        row.description = value.description();
        row.logoUrl = value.logoUrl();
        row.audit(value.audit());
        return row;
    }

    public CompanyRecord value() {
        return new CompanyRecord(new Company(id, tenantId, partyId, status, new AggregateVersion(version)),
                code, operatingName, description, logoUrl, audit());
    }
}
