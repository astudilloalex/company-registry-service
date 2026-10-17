package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.OutboxRecord;
import com.alexastudillo.companyregistry.domain.model.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "company_outbox_events")
public class OutboxEntity extends AuditedRow {
    @Id public UUID id;
    @Column(name = "tenant_id", nullable = false) public UUID tenantId;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "aggregate_type", nullable = false, columnDefinition = "company_outbox_aggregate_type") public AggregateType aggregateType;
    @Column(name = "aggregate_id", nullable = false) public UUID aggregateId;
    @Column(name = "aggregate_version", nullable = false) public long aggregateVersion;
    @Column(name = "event_type", nullable = false, length = 128) public String eventType;
    @Column(name = "event_schema_version", nullable = false) public short schemaVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") public Map<String, Object> payload;
    @Column(name = "occurred_at", nullable = false) public Instant occurredAt;
    @Column(name = "correlation_id", nullable = false, length = 128) public String correlationId;
    @Column(name = "causation_id", length = 128) public String causationId;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "outbox_status") public OutboxStatus status;
    @Column(name = "publish_attempts", nullable = false) public int attempts;
    @Column(name = "next_attempt_at") public Instant nextAttemptAt;
    @Column(name = "last_attempt_at") public Instant lastAttemptAt;
    @Column(name = "published_at") public Instant publishedAt;
    @Column(name = "last_error_code", length = 64) public String errorCode;
    @Column(name = "last_error_detail", length = 1000) public String errorDetail;
    @Version @Column(nullable = false) public long version;
    public static OutboxEntity from(OutboxRecord value) {
        var row = new OutboxEntity(); row.id = value.id(); row.tenantId = value.tenantId();
        row.aggregateType = value.aggregateType(); row.aggregateId = value.aggregateId(); row.aggregateVersion = value.aggregateVersion();
        row.eventType = value.eventType(); row.schemaVersion = value.schemaVersion(); row.payload = value.payload(); row.occurredAt = value.occurredAt();
        row.correlationId = value.correlationId(); row.causationId = value.causationId(); row.status = value.status(); row.attempts = value.attempts();
        row.nextAttemptAt = value.nextAttemptAt(); row.lastAttemptAt = value.lastAttemptAt(); row.publishedAt = value.publishedAt();
        row.errorCode = value.errorCode(); row.errorDetail = value.errorDetail(); row.audit(value.audit()); row.version = value.version(); return row;
    }
    public OutboxRecord value() {
        return new OutboxRecord(id, tenantId, aggregateType, aggregateId, aggregateVersion, eventType, schemaVersion,
                payload, occurredAt, correlationId, causationId, status, attempts, nextAttemptAt, lastAttemptAt,
                publishedAt, errorCode, errorDetail, audit(), version);
    }
}
