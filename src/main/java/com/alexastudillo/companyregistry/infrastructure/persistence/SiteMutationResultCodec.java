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
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

/** Immutable acceptance format one, independent of current aggregate state. */
@ApplicationScoped
public class SiteMutationResultCodec implements SiteMutationResults {
    private static final String COMPANY_STATUS = "companyStatus";
    private static final String EFFECTIVELY_OPERATIONAL = "effectivelyOperational";

    private static final ObjectMapper V1 = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Override public short currentVersion() { return 1; }

    @Override public Map<String, Object> encode(SiteMutation.Result result) {
        validate(result);
        var document = new java.util.LinkedHashMap<String, Object>();
        document.put("site", V1.convertValue(result.site(), new TypeReference<Map<String, Object>>() {}));
        document.put(COMPANY_STATUS, result.companyStatus().name());
        document.put(EFFECTIVELY_OPERATIONAL, result.effectivelyOperational());
        return java.util.Collections.unmodifiableMap(document);
    }

    @Override public SiteMutation.Result decode(CompletionRecord stored) {
        if (stored.snapshotVersion() != 1) throw new IllegalStateException("historical-result-version-unavailable");
        try {
            var operation = java.util.Arrays.stream(SiteMutation.Operation.values())
                    .filter(value -> value.namespace().equals(stored.key().operation())).findFirst().orElseThrow();
            metadata(stored);
            shape(stored.snapshot());
            var row = V1.convertValue(stored.snapshot().get("site"),
                    com.alexastudillo.companyregistry.application.persistence.RegistryData.SiteRecord.class);
            var parentStatus = CompanyStatus.valueOf((String) stored.snapshot().get(COMPANY_STATUS));
            var result = new SiteMutation.Result(row, parentStatus);
            validate(result);
            validateStored(stored, result, operation);
            return result;
        } catch (RuntimeException _) {
            throw new IllegalStateException("historical-site-result-invalid");
        }
    }

    private static void metadata(CompletionRecord stored) {
        if (stored.resultStatus() != 200 || stored.siteId() == null
                || stored.expectedVersion() == null || stored.expectedVersion() < 0
                || stored.fingerprintVersion() == null || stored.fingerprintVersion() <= 0
                || stored.fingerprintKeyId() == null || !stored.fingerprintKeyId().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException();
        }
    }

    private static void validateStored(CompletionRecord stored, SiteMutation.Result result, SiteMutation.Operation operation) {
        var site = result.site().site();
        long expected = stored.expectedVersion();
        long version = site.version().value();
        boolean changed = version != expected;
        if (changed && (expected == Long.MAX_VALUE || version != expected + 1)) throw new IllegalArgumentException();
        if (operation != SiteMutation.Operation.UPDATE && (!changed || site.status() != operation.target())) {
            throw new IllegalArgumentException();
        }
        if (operation == SiteMutation.Operation.UPDATE && site.status() == SiteStatus.CLOSED) throw new IllegalArgumentException();
        if (!site.id().equals(stored.siteId()) || !site.companyId().equals(stored.companyId())
                || !site.tenantId().equals(stored.key().tenantId())
                || !Boolean.valueOf(result.effectivelyOperational()).equals(stored.snapshot().get(EFFECTIVELY_OPERATIONAL))) {
            throw new IllegalArgumentException();
        }
        if (changed && (!result.site().audit().updatedAt().equals(stored.createdAt())
                || !result.site().audit().updatedBy().equals(stored.createdBy()))) throw new IllegalArgumentException();
        OpaqueValue.required(stored.createdBy(), 128);
        // A no-op records an independent acceptance clock; node clocks need not order Site's unchanged audit.
        if (stored.createdAt() == null || !stored.createdAt().equals(stored.createdAt().truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException();
        }
    }

    private static void shape(Map<String, Object> document) {
        keys(document, "site", COMPANY_STATUS, EFFECTIVELY_OPERATIONAL);
        string(document.get(COMPANY_STATUS));
        if (!(document.get(EFFECTIVELY_OPERATIONAL) instanceof Boolean)) throw new IllegalArgumentException();
        var row = object(document.get("site"));
        keys(row, "site", "code", "name", "audit");
        string(row.get("code")); string(row.get("name"));
        var site = object(row.get("site"));
        keys(site, "id", "tenantId", "companyId", "type", "status", "version");
        for (var key : new String[] {"id", "tenantId", "companyId", "type", "status"}) string(site.get(key));
        var version = object(site.get("version"));
        keys(version, "value");
        var value = version.get("value");
        if (!(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof java.math.BigInteger)) throw new IllegalArgumentException();
        if (value instanceof java.math.BigInteger number && number.longValueExact() < 0) throw new IllegalArgumentException();
        var audit = object(row.get("audit"));
        keys(audit, "createdAt", "createdBy", "updatedAt", "updatedBy");
        for (var text : audit.values()) string(text);
        for (var field : new String[] {"createdAt", "updatedAt"}) {
            if (!((String) audit.get(field)).endsWith("Z")) throw new IllegalArgumentException();
        }
    }

    private static Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException();
    }
    private static void keys(Map<?, ?> value, String... keys) {
        if (value == null || !value.keySet().equals(Set.of(keys))) throw new IllegalArgumentException();
    }
    private static void string(Object value) {
        if (!(value instanceof String)) throw new IllegalArgumentException();
    }
    private static void validate(SiteMutation.Result result) {
        var row = result.site();
        var site = row.site();
        var input = SiteRegistrationPolicy.canonical(row.code(), row.name(), FieldChange.present(site.type().name()));
        if (!input.code().equals(row.code()) || !input.name().equals(row.name())
                || result.companyStatus() == CompanyStatus.ARCHIVED || site.id().version() != 7) throw new IllegalArgumentException();
        var audit = row.audit();
        OpaqueValue.required(audit.createdBy(), 128);
        OpaqueValue.required(audit.updatedBy(), 128);
        if (audit.createdAt() == null || audit.updatedAt() == null || audit.updatedAt().isBefore(audit.createdAt())
                || !audit.createdAt().equals(audit.createdAt().truncatedTo(ChronoUnit.MICROS))
                || !audit.updatedAt().equals(audit.updatedAt().truncatedTo(ChronoUnit.MICROS))) throw new IllegalArgumentException();
    }
}
