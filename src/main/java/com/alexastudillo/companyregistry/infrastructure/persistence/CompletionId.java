package com.alexastudillo.companyregistry.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompletionKey;

@Embeddable
public class CompletionId implements Serializable {
    @Column(name = "tenant_id", nullable = false) public UUID tenantId;
    @Column(nullable = false, length = 64) public String operation;
    @Column(name = "idempotency_key", nullable = false, length = 128) public String key;
    public CompletionId() {}
    public CompletionId(CompletionKey value) { tenantId = value.tenantId(); operation = value.operation(); key = value.key(); }
    public CompletionKey value() { return new CompletionKey(tenantId, operation, key); }
    @Override public boolean equals(Object other) {
        return other instanceof CompletionId id && Objects.equals(tenantId, id.tenantId)
                && Objects.equals(operation, id.operation) && Objects.equals(key, id.key);
    }
    @Override public int hashCode() { return Objects.hash(tenantId, operation, key); }
}
