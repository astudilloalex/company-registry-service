package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.Audit;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;

@MappedSuperclass
public abstract class AuditedRow {
    @Column(name = "created_at", nullable = false, updatable = false) public Instant createdAt;
    @Column(name = "created_by", nullable = false, updatable = false, length = 128) public String createdBy;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
    @Column(name = "updated_by", nullable = false, length = 128) public String updatedBy;

    public Audit audit() { return new Audit(createdAt, createdBy, updatedAt, updatedBy); }
    public void audit(Audit audit) {
        createdAt = audit.createdAt(); createdBy = audit.createdBy();
        updatedAt = audit.updatedAt(); updatedBy = audit.updatedBy();
    }
}
