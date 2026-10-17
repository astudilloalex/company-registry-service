package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompletionRecord;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "company_idempotency_records")
public class CompletionEntity {
    @EmbeddedId public CompletionId id;
    @Column(name = "request_hash", nullable = false, columnDefinition = "char(64)") public String hash;
    @Column(name = "fingerprint_version") public Short fingerprintVersion;
    @Column(name = "fingerprint_key_id", length = 64) public String fingerprintKeyId;
    @Column(name = "company_id", nullable = false) public UUID companyId;
    @Column(name = "site_id") public UUID siteId;
    @Column(name = "expected_aggregate_version") public Long expectedVersion;
    @Column(name = "response_http_status", nullable = false) public short resultStatus;
    @Column(name = "result_snapshot_schema_version", nullable = false) public short snapshotVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "result_snapshot", nullable = false, columnDefinition = "jsonb") public Map<String, Object> snapshot;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "created_by", nullable = false, length = 128) public String createdBy;
    public static CompletionEntity from(CompletionRecord value) {
        var row = new CompletionEntity(); row.id = new CompletionId(value.key()); row.hash = value.requestHash();
        row.fingerprintVersion = value.fingerprintVersion(); row.fingerprintKeyId = value.fingerprintKeyId();
        row.companyId = value.companyId(); row.siteId = value.siteId(); row.expectedVersion = value.expectedVersion();
        row.resultStatus = value.resultStatus(); row.snapshotVersion = value.snapshotVersion(); row.snapshot = value.snapshot();
        row.createdAt = value.createdAt(); row.createdBy = value.createdBy(); return row;
    }
    public CompletionRecord value() {
        return new CompletionRecord(id.value(), hash, companyId, siteId, expectedVersion, resultStatus,
                snapshotVersion, snapshot, createdAt, createdBy, fingerprintVersion, fingerprintKeyId);
    }
}
