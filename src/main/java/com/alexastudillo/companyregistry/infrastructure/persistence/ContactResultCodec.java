package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompletionRecord;
import com.alexastudillo.companyregistry.application.site.*;
import com.alexastudillo.companyregistry.domain.model.*;
import com.alexastudillo.companyregistry.domain.text.OpaqueValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Closed HU07 format one. Decoding never consults current contacts or provider data. */
@ApplicationScoped
public class ContactResultCodec implements ContactResults {
    private static final String CONTACT_ID = "contactId";
    private static final String OPERATION = "operation";
    private static final String RESULT = "result";
    private static final String SCOPE = "scope";
    private static final String SITE_ID = "siteId";
    private static final String SITE_VERSION = "siteVersion";
    private static final String DELETED_AT = "deletedAt";
    private static final String DELETED_BY = "deletedBy";
    private static final String LABEL = "label";

    private static final ObjectMapper V1 = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Override public short currentVersion() { return 1; }

    @Override public Map<String, Object> encode(ContactMutation.Operation operation, ContactMutation.Result result) {
        validate(result);
        if (operation == ContactMutation.Operation.CREATE && result.contactId().version() != 7) throw invalid();
        if ((operation == ContactMutation.Operation.DELETE) != (result instanceof ContactMutation.Deletion)) throw invalid();
        var document = new LinkedHashMap<String, Object>();
        document.put(OPERATION, operation.namespace());
        document.put(RESULT, V1.convertValue(result, new TypeReference<Map<String, Object>>() {}));
        return Collections.unmodifiableMap(document);
    }

    @Override public ContactMutation.Result decode(CompletionRecord stored, UUID expectedContactId) {
        try {
            var operation = Arrays.stream(ContactMutation.Operation.values())
                    .filter(value -> value.namespace().equals(stored.key().operation())).findFirst().orElseThrow();
            metadata(stored, operation);
            keys(stored.snapshot(), OPERATION, RESULT);
            if (!operation.namespace().equals(stored.snapshot().get(OPERATION))) throw invalid();
            var result = object(stored.snapshot().get(RESULT));
            shape(result, operation);
            ContactMutation.Result decoded = operation == ContactMutation.Operation.DELETE
                    ? V1.convertValue(result, ContactMutation.Deletion.class) : V1.convertValue(result, ContactMutation.View.class);
            validate(decoded);
            stored(stored, decoded, operation, expectedContactId);
            return decoded;
        } catch (RuntimeException _) {
            throw invalid();
        }
    }

    private static void metadata(CompletionRecord stored, ContactMutation.Operation operation) {
        if (stored.snapshotVersion() != 1 || stored.resultStatus() != operation.resultStatus() || stored.siteId() == null
                || stored.expectedVersion() == null || stored.expectedVersion() < 0 || stored.fingerprintVersion() == null
                || stored.fingerprintVersion() <= 0 || stored.fingerprintKeyId() == null
                || !stored.fingerprintKeyId().matches("[A-Za-z0-9_-]{1,64}")) throw invalid();
        timestamp(stored.createdAt());
        OpaqueValue.requireValid(stored.createdBy(), 128);
    }

    private static void stored(CompletionRecord stored, ContactMutation.Result result, ContactMutation.Operation operation,
            UUID expectedContactId) {
        validateStoredIdentity(stored, result, expectedContactId);
        boolean changed = validateStoredVersion(stored, result, operation);
        validateStoredOperation(operation, result, expectedContactId);
        validateStoredAudit(stored, result, operation, changed);
    }

    private static void validateStoredIdentity(CompletionRecord stored, ContactMutation.Result result, UUID expectedContactId) {
        var scope = result.scope();
        if (!scope.tenantId().equals(stored.key().tenantId()) || !scope.companyId().equals(stored.companyId())
                || !scope.siteId().equals(stored.siteId())
                || (expectedContactId != null && !expectedContactId.equals(result.contactId()))) throw invalid();
    }

    private static boolean validateStoredVersion(CompletionRecord stored, ContactMutation.Result result, ContactMutation.Operation operation) {
        long expected = stored.expectedVersion();
        boolean changed = result.siteVersion() != expected;
        if (changed && (expected == Long.MAX_VALUE || result.siteVersion() != expected + 1)) throw invalid();
        if (operation != ContactMutation.Operation.UPDATE && !changed) throw invalid();
        return changed;
    }

    private static void validateStoredOperation(ContactMutation.Operation operation, ContactMutation.Result result, UUID expectedContactId) {
        if (operation == ContactMutation.Operation.CREATE) {
            if (expectedContactId != null || result.contactId().version() != 7) throw invalid();
        } else if (expectedContactId == null) {
            throw invalid();
        }
    }

    private static void validateStoredAudit(CompletionRecord stored, ContactMutation.Result result, ContactMutation.Operation operation,
            boolean changed) {
        if (result instanceof ContactMutation.View view) {
            var audit = view.contact().audit();
            if (changed && (!audit.updatedAt().equals(stored.createdAt()) || !audit.updatedBy().equals(stored.createdBy()))) throw invalid();
            if (operation == ContactMutation.Operation.CREATE
                    && (!audit.createdAt().equals(stored.createdAt()) || !audit.createdBy().equals(stored.createdBy()))) throw invalid();
        } else if (result instanceof ContactMutation.Deletion deletion
                && (!deletion.deletedAt().equals(stored.createdAt()) || !deletion.deletedBy().equals(stored.createdBy()))) throw invalid();
    }

    private static void validate(ContactMutation.Result result) {
        if (result.siteVersion() < 0) throw invalid();
        if (result instanceof ContactMutation.View view) {
            validateView(view);
        } else if (result instanceof ContactMutation.Deletion deletion) {
            validateDeletion(deletion);
        }
    }

    private static void validateView(ContactMutation.View view) {
        var row = view.contact();
        if (row.type() == null || row.value() == null) throw invalid();
        if (row.type() == ContactType.EMAIL) {
            if (!row.value().equals(com.alexastudillo.companyregistry.domain.text.ContactValue.normalize(ContactType.EMAIL, row.value()))) throw invalid();
        } else if (!row.value().matches("\\+[1-9]\\d{1,14}")) {
            // Historical results must not be revalidated with current numbering metadata.
            throw invalid();
        }
        if (!Objects.equals(row.label(), ContactInformationPolicy.label(row.label()))) throw invalid();
        var audit = row.audit();
        timestamp(audit.createdAt()); timestamp(audit.updatedAt());
        if (audit.updatedAt().isBefore(audit.createdAt())) throw invalid();
        OpaqueValue.requireValid(audit.createdBy(), 128); OpaqueValue.requireValid(audit.updatedBy(), 128);
    }

    private static void validateDeletion(ContactMutation.Deletion deletion) {
        timestamp(deletion.deletedAt());
        OpaqueValue.requireValid(deletion.deletedBy(), 128);
    }

    private static void shape(Map<?, ?> result, ContactMutation.Operation operation) {
        var scope = object(result.get(SCOPE));
        keys(scope, "tenantId", "companyId", SITE_ID);
        scope.values().forEach(ContactResultCodec::string);
        integral(result.get(SITE_VERSION));
        if (operation == ContactMutation.Operation.DELETE) {
            keys(result, SCOPE, CONTACT_ID, SITE_VERSION, DELETED_AT, DELETED_BY);
            string(result.get(CONTACT_ID)); utcString(result.get(DELETED_AT)); string(result.get(DELETED_BY));
            return;
        }
        keys(result, SCOPE, "contact", SITE_VERSION);
        var row = object(result.get("contact"));
        keys(row, "id", SITE_ID, "type", "value", LABEL, "primary", "audit", DELETED_AT, DELETED_BY);
        for (var key : new String[] {"id", SITE_ID, "type", "value"}) string(row.get(key));
        if (row.get(LABEL) != null) string(row.get(LABEL));
        if (!(row.get("primary") instanceof Boolean) || row.get(DELETED_AT) != null || row.get(DELETED_BY) != null) throw invalid();
        var audit = object(row.get("audit"));
        keys(audit, "createdAt", "createdBy", "updatedAt", "updatedBy");
        utcString(audit.get("createdAt")); utcString(audit.get("updatedAt"));
        string(audit.get("createdBy")); string(audit.get("updatedBy"));
    }

    private static void integral(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger)) throw invalid();
        if (value instanceof java.math.BigInteger number && number.longValueExact() < 0) throw invalid();
    }

    private static Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw invalid();
    }

    private static void keys(Map<?, ?> map, String... keys) {
        if (map == null || !map.keySet().equals(Set.of(keys))) throw invalid();
    }

    private static void string(Object value) {
        if (!(value instanceof String)) throw invalid();
    }

    private static void utcString(Object value) {
        if (!(value instanceof String text) || !text.endsWith("Z")) throw invalid();
    }

    private static void timestamp(Instant instant) {
        if (instant == null || !instant.equals(instant.truncatedTo(ChronoUnit.MICROS))) throw invalid();
    }

    private static IllegalStateException invalid() { return new IllegalStateException("historical-contact-result-invalid"); }
}
