package com.alexastudillo.companyregistry.application.persistence;

import com.alexastudillo.companyregistry.domain.model.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Neutral stored-operation projections, independent of HTTP, ORM and JSON libraries. */
public final class RegistryData {
    private RegistryData() {}

    public record Audit(Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {}
    public record CompanyRecord(Company company, String code, String operatingName,
                                String description, String logoUrl, Audit audit) {}
    public record SiteRecord(Site site, String code, String name, Audit audit) {}
    public record AddressRecord(UUID id, UUID siteId, AddressType type, String countryCode,
                                String administrativeDivisionCode, String line1, String line2,
                                String houseNumber, String postalCode, BigDecimal latitude,
                                BigDecimal longitude, boolean primary, Audit audit,
                                Instant deletedAt, String deletedBy) {}
    public record ContactRecord(UUID id, UUID siteId, ContactType type, String value,
                                String label, boolean primary, Audit audit,
                                Instant deletedAt, String deletedBy) {}
    public record CompletionKey(UUID tenantId, String operation, String key) {}
    /** Accepted result metadata; the numeric status is opaque persisted outcome metadata, not an HTTP API type. */
    public record CompletionRecord(CompletionKey key, String requestHash, UUID companyId, UUID siteId,
                                   Long expectedVersion, short resultStatus, short snapshotVersion,
                                   Map<String, Object> snapshot, Instant createdAt, String createdBy) {}
    public record OutboxRecord(UUID id, UUID tenantId, AggregateType aggregateType, UUID aggregateId,
                               long aggregateVersion, String eventType, short schemaVersion,
                               Map<String, Object> payload, Instant occurredAt, String correlationId,
                               String causationId, OutboxStatus status, int attempts, Instant nextAttemptAt,
                               Instant lastAttemptAt, Instant publishedAt, String errorCode, String errorDetail,
                               Audit audit, long version) {}
}
