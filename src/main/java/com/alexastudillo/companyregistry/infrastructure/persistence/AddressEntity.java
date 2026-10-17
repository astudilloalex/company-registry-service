package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.AddressRecord;
import com.alexastudillo.companyregistry.domain.model.AddressType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "company_site_addresses")
public class AddressEntity extends AuditedRow {
    @Id public UUID id;
    @Column(name = "site_id", nullable = false, updatable = false) public UUID siteId;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "address_type", nullable = false, columnDefinition = "company_site_address_type") public AddressType type;
    @Column(name = "country_code", nullable = false, columnDefinition = "char(2)") public String countryCode;
    @Column(name = "administrative_division_code", length = 64) public String division;
    @Column(name = "address_line_1", nullable = false, length = 300) public String line1;
    @Column(name = "address_line_2", length = 300) public String line2;
    @Column(name = "house_number", length = 32) public String houseNumber;
    @Column(name = "postal_code", length = 32) public String postalCode;
    @Column(precision = 9, scale = 6) public BigDecimal latitude;
    @Column(precision = 9, scale = 6) public BigDecimal longitude;
    @Column(name = "is_primary", nullable = false) public boolean primaryValue;
    @Column(name = "deleted_at") public Instant deletedAt;
    @Column(name = "deleted_by", length = 128) public String deletedBy;

    public static AddressEntity from(AddressRecord value) {
        var row = new AddressEntity(); row.id = value.id(); row.siteId = value.siteId(); row.type = value.type();
        row.countryCode = value.countryCode(); row.division = value.administrativeDivisionCode();
        row.line1 = value.line1(); row.line2 = value.line2(); row.houseNumber = value.houseNumber(); row.postalCode = value.postalCode();
        row.latitude = value.latitude(); row.longitude = value.longitude(); row.primaryValue = value.primary();
        row.audit(value.audit()); row.deletedAt = value.deletedAt(); row.deletedBy = value.deletedBy(); return row;
    }
    public AddressRecord value() {
        return new AddressRecord(id, siteId, type, countryCode, division, line1, line2, houseNumber,
                postalCode, latitude, longitude, primaryValue, audit(), deletedAt, deletedBy);
    }
}
