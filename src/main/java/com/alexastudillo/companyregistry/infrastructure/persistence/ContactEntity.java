package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.ContactRecord;
import com.alexastudillo.companyregistry.domain.model.ContactType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "company_site_contact_points")
public class ContactEntity extends AuditedRow {
    @Id
    public UUID id;
    @Column(name = "site_id", nullable = false, updatable = false)
    public UUID siteId;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "contact_type", nullable = false, columnDefinition = "company_site_contact_type")
    public ContactType type;
    @Column(nullable = false, length = 320)
    public String value;
    @Column(length = 80)
    public String label;
    @Column(name = "is_primary", nullable = false)
    public boolean primaryValue;
    @Column(name = "deleted_at")
    public Instant deletedAt;
    @Column(name = "deleted_by", length = 128)
    public String deletedBy;

    public static ContactEntity from(ContactRecord value) {
        var row = new ContactEntity();
        row.id = value.id();
        row.siteId = value.siteId();
        row.type = value.type();
        row.value = value.value();
        row.label = value.label();
        row.primaryValue = value.primary();
        row.audit(value.audit());
        row.deletedAt = value.deletedAt();
        row.deletedBy = value.deletedBy();
        return row;
    }

    public ContactRecord contactRecord() {
        return new ContactRecord(id, siteId, type, value, label, primaryValue, audit(), deletedAt, deletedBy);
    }
}
