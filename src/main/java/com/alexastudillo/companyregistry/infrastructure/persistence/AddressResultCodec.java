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

/** Closed HU06 format one. Decoding never consults current addresses or provider data. */
@ApplicationScoped
public class AddressResultCodec implements AddressResults {
    private static final String OPERATION = "operation";
    private static final String RESULT = "result";
    private static final String SCOPE = "scope";
    private static final String SITE_ID = "siteId";
    private static final String SITE_VERSION = "siteVersion";
    private static final String DELETED_AT = "deletedAt";
    private static final String DELETED_BY = "deletedBy";

    private static final ObjectMapper V1 = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Override public short currentVersion() { return 1; }

    @Override public Map<String, Object> encode(AddressMutation.Operation operation, AddressMutation.Result result) {
        result = canonicalCoordinates(result);
        validate(result);
        if (operation == AddressMutation.Operation.CREATE && result.addressId().version() != 7) throw invalid();
        if ((operation == AddressMutation.Operation.DELETE) != (result instanceof AddressMutation.Deletion)) throw invalid();
        var document = new LinkedHashMap<String, Object>();
        document.put(OPERATION, operation.namespace());
        document.put(RESULT, V1.convertValue(result, new TypeReference<Map<String, Object>>() {}));
        return Collections.unmodifiableMap(document);
    }

    @Override public AddressMutation.Result decode(CompletionRecord stored, UUID expectedAddressId) {
        try {
            var operation = Arrays.stream(AddressMutation.Operation.values())
                    .filter(value -> value.namespace().equals(stored.key().operation())).findFirst().orElseThrow();
            metadata(stored, operation);
            keys(stored.snapshot(), OPERATION, RESULT);
            if (!operation.namespace().equals(stored.snapshot().get(OPERATION))) throw invalid();
            var result = object(stored.snapshot().get(RESULT));
            shape(result, operation);
            AddressMutation.Result decoded = operation == AddressMutation.Operation.DELETE
                    ? V1.convertValue(result, AddressMutation.Deletion.class) : V1.convertValue(result, AddressMutation.View.class);
            decoded = canonicalCoordinates(decoded);
            validate(decoded);
            stored(stored, decoded, operation, expectedAddressId);
            return decoded;
        } catch (RuntimeException _) {
            throw invalid();
        }
    }

    private static void metadata(CompletionRecord stored, AddressMutation.Operation operation) {
        if (stored.snapshotVersion() != 1 || stored.resultStatus() != operation.resultStatus() || stored.siteId() == null
                || stored.expectedVersion() == null || stored.expectedVersion() < 0 || stored.fingerprintVersion() == null
                || stored.fingerprintVersion() <= 0 || stored.fingerprintKeyId() == null
                || !stored.fingerprintKeyId().matches("[A-Za-z0-9_-]{1,64}")) throw invalid();
        timestamp(stored.createdAt());
        OpaqueValue.requireValid(stored.createdBy(), 128);
    }

    private static void stored(CompletionRecord stored, AddressMutation.Result result, AddressMutation.Operation operation,
            UUID expectedAddressId) {
        validateStoredIdentity(stored, result, expectedAddressId);
        boolean changed = validateStoredVersion(stored, result, operation);
        validateStoredOperation(operation, result, expectedAddressId);
        validateStoredAudit(stored, result, operation, changed);
    }

    private static void validateStoredIdentity(CompletionRecord stored, AddressMutation.Result result, UUID expectedAddressId) {
        var scope = result.scope();
        if (!scope.tenantId().equals(stored.key().tenantId()) || !scope.companyId().equals(stored.companyId())
                || !scope.siteId().equals(stored.siteId())
                || (expectedAddressId != null && !expectedAddressId.equals(result.addressId()))) throw invalid();
    }

    private static boolean validateStoredVersion(CompletionRecord stored, AddressMutation.Result result, AddressMutation.Operation operation) {
        long expected = stored.expectedVersion();
        boolean changed = result.siteVersion() != expected;
        if (changed && (expected == Long.MAX_VALUE || result.siteVersion() != expected + 1)) throw invalid();
        if (operation != AddressMutation.Operation.UPDATE && !changed) throw invalid();
        return changed;
    }

    private static void validateStoredOperation(AddressMutation.Operation operation, AddressMutation.Result result, UUID expectedAddressId) {
        if (operation == AddressMutation.Operation.CREATE) {
            if (expectedAddressId != null || result.addressId().version() != 7) throw invalid();
        } else if (expectedAddressId == null) {
            throw invalid();
        }
    }

    private static void validateStoredAudit(CompletionRecord stored, AddressMutation.Result result, AddressMutation.Operation operation,
            boolean changed) {
        if (result instanceof AddressMutation.View view) {
            var audit = view.address().audit();
            if (changed && (!audit.updatedAt().equals(stored.createdAt()) || !audit.updatedBy().equals(stored.createdBy()))) throw invalid();
            if (operation == AddressMutation.Operation.CREATE
                    && (!audit.createdAt().equals(stored.createdAt()) || !audit.createdBy().equals(stored.createdBy()))) throw invalid();
        } else if (result instanceof AddressMutation.Deletion deletion
                && (!deletion.deletedAt().equals(stored.createdAt()) || !deletion.deletedBy().equals(stored.createdBy()))) throw invalid();
    }

    private static void validate(AddressMutation.Result result) {
        if (result.siteVersion() < 0) throw invalid();
        if (result instanceof AddressMutation.View view) {
            var row = view.address();
            var text = new AddressText(row.countryCode(), row.line1(), row.administrativeDivisionCode(),
                    row.line2(), row.houseNumber(), row.postalCode());
            if (!Objects.equals(text.countryCode(), row.countryCode()) || !Objects.equals(text.addressLine1(), row.line1())
                    || !Objects.equals(text.administrativeDivisionCode(), row.administrativeDivisionCode())
                    || !Objects.equals(text.addressLine2(), row.line2()) || !Objects.equals(text.houseNumber(), row.houseNumber())
                    || !Objects.equals(text.postalCode(), row.postalCode()) || row.type() == null) throw invalid();
            var audit = row.audit();
            timestamp(audit.createdAt()); timestamp(audit.updatedAt());
            if (audit.updatedAt().isBefore(audit.createdAt())) throw invalid();
            OpaqueValue.requireValid(audit.createdBy(), 128); OpaqueValue.requireValid(audit.updatedBy(), 128);
        } else if (result instanceof AddressMutation.Deletion deletion) {
            timestamp(deletion.deletedAt());
            OpaqueValue.requireValid(deletion.deletedBy(), 128);
        }
    }

    /** JSONB may change numeric scale; format one defines numeric canonical equality, never rounding. */
    private static AddressMutation.Result canonicalCoordinates(AddressMutation.Result result) {
        if (result instanceof AddressMutation.View(var scope, var row, var siteVersion)) {
            var coordinates = new AddressCoordinates(row.latitude(), row.longitude());
            var canonical = new com.alexastudillo.companyregistry.application.persistence.RegistryData.AddressRecord(
                    row.id(), row.siteId(), row.type(), row.countryCode(), row.administrativeDivisionCode(), row.line1(), row.line2(),
                    row.houseNumber(), row.postalCode(), coordinates.latitude(), coordinates.longitude(), row.primary(), row.audit(),
                    row.deletedAt(), row.deletedBy());
            return new AddressMutation.View(scope, canonical, siteVersion);
        }
        return result;
    }

    private static void shape(Map<?, ?> result, AddressMutation.Operation operation) {
        var scope = object(result.get(SCOPE));
        keys(scope, "tenantId", "companyId", SITE_ID);
        scope.values().forEach(AddressResultCodec::string);
        integral(result.get(SITE_VERSION));
        if (operation == AddressMutation.Operation.DELETE) {
            keys(result, SCOPE, "addressId", SITE_VERSION, DELETED_AT, DELETED_BY);
            string(result.get("addressId")); utcString(result.get(DELETED_AT)); string(result.get(DELETED_BY));
            return;
        }
        keys(result, SCOPE, "address", SITE_VERSION);
        var row = object(result.get("address"));
        keys(row, "id", SITE_ID, "type", "countryCode", "administrativeDivisionCode", "line1", "line2",
                "houseNumber", "postalCode", "latitude", "longitude", "primary", "audit", DELETED_AT, DELETED_BY);
        for (var key : new String[] {"id", SITE_ID, "type", "countryCode", "line1"}) string(row.get(key));
        for (var key : new String[] {"administrativeDivisionCode", "line2", "houseNumber", "postalCode"}) {
            if (row.get(key) != null) string(row.get(key));
        }
        if (!(row.get("primary") instanceof Boolean) || row.get(DELETED_AT) != null || row.get(DELETED_BY) != null) throw invalid();
        for (var key : new String[] {"latitude", "longitude"}) {
            if (row.get(key) != null && !(row.get(key) instanceof Number)) throw invalid();
        }
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

    private static IllegalStateException invalid() { return new IllegalStateException("historical-address-result-invalid"); }
}
