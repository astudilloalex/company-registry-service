package com.alexastudillo.companyregistry.infrastructure.outbox;

import com.alexastudillo.companyregistry.application.outbox.DeliveryFailure;
import com.alexastudillo.companyregistry.application.outbox.StoredEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Closed V1 contract validation. Nothing is fetched from mutable business aggregates. */
@ApplicationScoped
public class StoredEventSerializer {
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final String COMPANY_ID = "companyId";
    private static final String SITE_ID = "siteId";
    private static final String SITE_TYPE = "siteType";
    private static final String STATUS = "status";
    private static final String DRAFT = "DRAFT";
    private static final String ACTIVE = "ACTIVE";
    private static final String INACTIVE = "INACTIVE";
    private final ObjectMapper json;

    @Inject public StoredEventSerializer(ObjectMapper json) { this.json = json; }

    public byte[] serialize(StoredEvent fact) {
        try {
            validate(fact);
        } catch (DeliveryFailure failure) { throw failure;
        } catch (RuntimeException _) { throw new DeliveryFailure(DeliveryFailure.Code.INVALID_CONTRACT); }
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("eventId", fact.eventId().toString());
        envelope.put("tenantId", fact.tenantId().toString());
        envelope.put("aggregateType", fact.aggregate().type().name());
        envelope.put("aggregateId", fact.aggregate().id().toString());
        envelope.put("aggregateVersion", fact.aggregate().version());
        envelope.put("eventType", fact.kind().type());
        envelope.put("eventSchemaVersion", fact.kind().schemaVersion());
        envelope.put("occurredAt", fact.kind().occurredAt().toString());
        envelope.put("correlationId", fact.context().correlationId());
        envelope.put("causationId", fact.context().causationId());
        envelope.put("payload", fact.payload());
        try { return json.writeValueAsBytes(envelope); }
        catch (JsonProcessingException | RuntimeException _) {
            throw new DeliveryFailure(DeliveryFailure.Code.SERIALIZATION_FAILED);
        }
    }

    private static void validate(StoredEvent fact) {
        var type = fact.kind().type();
        if (!OutboxDestination.EVENT_TYPES.contains(type) || fact.kind().schemaVersion() != 1)
            throw new DeliveryFailure(DeliveryFailure.Code.UNSUPPORTED_EVENT);
        require(v7(fact.eventId()) && v7(fact.aggregate().id()) && fact.tenantId() != null);
        require(!fact.eventId().equals(fact.aggregate().id()));
        uuid(fact.context().correlationId(), false);
        require(fact.context().causationId() == null && fact.kind().occurredAt() != null);
        boolean site = type.startsWith("company.site.");
        boolean created = type.endsWith(".created.v1");
        require(fact.aggregate().type().name().equals(site ? "COMPANY_SITE" : "COMPANY"));
        require(created ? fact.aggregate().version() == 0 : fact.aggregate().version() > 0);
        payload(fact, site, created);
    }

    private static void payload(StoredEvent fact, boolean site, boolean created) {
        var p = fact.payload();
        String date = created ? "createdAt" : "updatedAt";
        String actor = created ? "createdBy" : "updatedBy";
        var fields = site ? Set.of(SITE_ID, COMPANY_ID, "code", "name", SITE_TYPE, STATUS, date, actor)
                : Set.of(COMPANY_ID, "partyId", "code", "operatingName", STATUS, date, actor);
        require(p.keySet().equals(fields));
        var id = uuid(text(p, site ? SITE_ID : COMPANY_ID, 36), true);
        require(id.equals(fact.aggregate().id()));
        uuid(text(p, site ? COMPANY_ID : "partyId", 36), site);
        text(p, "code", 64);
        text(p, site ? "name" : "operatingName", 200);
        text(p, actor, 128);
        String timestamp = text(p, date, 128);
        require(timestamp.endsWith("Z") && Instant.parse(timestamp).equals(fact.kind().occurredAt()));
        String status = text(p, STATUS, 16);
        require(statuses(fact.kind().type()).contains(status));
        if (site) require(Set.of("HEADQUARTERS", "BRANCH", "WAREHOUSE", "OTHER").contains(text(p, SITE_TYPE, 32)));
    }

    private static Set<String> statuses(String type) {
        if (type.endsWith(".created.v1")) return Set.of(DRAFT);
        return switch (type) {
            case "company.activated.v1" -> Set.of(ACTIVE);
            case "company.deactivated.v1" -> Set.of(INACTIVE);
            case "company.site.closed.v1" -> Set.of("CLOSED");
            case "company.updated.v1" -> Set.of(DRAFT, ACTIVE, INACTIVE, "ARCHIVED");
            default -> Set.of(DRAFT, ACTIVE, INACTIVE);
        };
    }

    private static String text(Map<String, Object> payload, String key, int maximum) {
        Object value = payload.get(key);
        require(value instanceof String);
        var text = (String) value;
        int length = text.codePointCount(0, text.length());
        require(length > 0 && length <= maximum);
        return text;
    }

    private static UUID uuid(String text, boolean version7) {
        require(text != null && UUID_TEXT.matcher(text).matches());
        var id = UUID.fromString(text);
        require(!version7 || v7(id));
        return id;
    }
    private static boolean v7(UUID id) { return id != null && id.version() == 7 && id.variant() == 2; }
    private static void require(boolean valid) { if (!valid) throw new DeliveryFailure(DeliveryFailure.Code.INVALID_CONTRACT); }
}
