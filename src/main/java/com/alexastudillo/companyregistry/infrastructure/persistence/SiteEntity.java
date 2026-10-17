package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.SiteRecord;
import com.alexastudillo.companyregistry.domain.model.*;
import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "company_sites")
public class SiteEntity extends AuditedRow {
    @Id
    public UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false)
    public UUID tenantId;
    @Column(name = "company_id", nullable = false, updatable = false)
    public UUID companyId;
    @Column(nullable = false, length = 64)
    public String code;
    @Column(nullable = false, length = 200)
    public String name;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "site_type", nullable = false, columnDefinition = "company_site_type")
    public SiteType type;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "company_site_status")
    public SiteStatus status;
    @Version
    @Column(nullable = false)
    public long version;

    public static SiteEntity from(SiteRecord value) {
        var row = new SiteEntity();
        var site = value.site();
        row.id = site.id();
        row.tenantId = site.tenantId();
        row.companyId = site.companyId();
        row.type = site.type();
        row.status = site.status();
        row.version = site.version().value();
        row.code = value.code();
        row.name = value.name();
        row.audit(value.audit());
        return row;
    }

    public SiteRecord value() {
        return new SiteRecord(new Site(id, tenantId, companyId, type, status, new AggregateVersion(version)), code,
                name, audit());
    }
}
