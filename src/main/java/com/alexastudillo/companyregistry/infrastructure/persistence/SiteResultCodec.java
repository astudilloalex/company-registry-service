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
public class SiteResultCodec implements SiteResults {
    private static final ObjectMapper V1 = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Override public short currentVersion() { return 1; }

    @Override public Map<String, Object> encode(SiteRegistration.Result result) {
        validate(result);
        return V1.convertValue(result, new TypeReference<>() {});
    }

    @Override public SiteRegistration.Result decode(CompletionRecord stored) {
        if (stored.snapshotVersion() != 1) throw new IllegalStateException("historical-result-version-unavailable");
        try {
            if (!SiteRegistration.OPERATION.equals(stored.key().operation()) || stored.resultStatus() != 201
                    || stored.siteId() == null || stored.expectedVersion() != null
                    || stored.fingerprintVersion() == null || stored.fingerprintVersion() <= 0
                    || stored.fingerprintKeyId() == null || !stored.fingerprintKeyId().matches("[A-Za-z0-9_-]{1,64}")) {
                throw new IllegalArgumentException();
            }
            shape(stored.snapshot());
            var result = V1.convertValue(stored.snapshot(), SiteRegistration.Result.class);
            validate(result);
            var site = result.site().site();
            if (!site.id().equals(stored.siteId()) || !site.companyId().equals(stored.companyId())
                    || !site.tenantId().equals(stored.key().tenantId())
                    || !result.site().audit().createdAt().equals(stored.createdAt())
                    || !result.site().audit().createdBy().equals(stored.createdBy())) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException _) {
            throw new IllegalStateException("historical-site-result-invalid");
        }
    }

    private static void shape(Map<String, Object> document) {
        keys(document, "site", "effectivelyOperational");
        if (!(document.get("effectivelyOperational") instanceof Boolean)) throw new IllegalArgumentException();
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
    private static void validate(SiteRegistration.Result result) {
        var row = result.site();
        var site = row.site();
        var input = SiteRegistrationPolicy.canonical(row.code(), row.name(), FieldChange.present(site.type().name()));
        if (!input.code().equals(row.code()) || !input.name().equals(row.name())
                || site.status() != SiteStatus.DRAFT || site.version().value() != 0 || result.effectivelyOperational()
                || site.id().version() != 7) throw new IllegalArgumentException();
        var audit = row.audit();
        OpaqueValue.required(audit.createdBy(), 128);
        if (!audit.createdBy().equals(audit.updatedBy()) || !audit.createdAt().equals(audit.updatedAt())
                || !audit.createdAt().equals(audit.createdAt().truncatedTo(ChronoUnit.MICROS))) throw new IllegalArgumentException();
    }
}
