package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.company.CompanyUpdate;
import com.alexastudillo.companyregistry.application.company.UpdateResults;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.domain.model.*;
import com.alexastudillo.companyregistry.domain.text.OpaqueValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class UpdateResultCodec implements UpdateResults {
    private static final ObjectMapper V1 = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Override public short currentVersion() { return 1; }
    @Override public Map<String, Object> encode(CompanyRecord result) {
        validate(result);
        return V1.convertValue(result, new TypeReference<>() {});
    }
    @Override public CompanyRecord decode(CompletionRecord stored) {
        if (stored.snapshotVersion() != 1) throw new IllegalStateException("historical-result-version-unavailable");
        try {
            if (!CompanyUpdate.OPERATION.equals(stored.key().operation()) || stored.resultStatus() != 200
                    || stored.siteId() != null || stored.expectedVersion() == null || stored.expectedVersion() < 0
                    || stored.fingerprintVersion() == null || stored.fingerprintVersion() <= 0
                    || stored.fingerprintKeyId() == null || !stored.fingerprintKeyId().matches("[A-Za-z0-9_-]{1,64}")) {
                throw new IllegalArgumentException();
            }
            validateShape(stored.snapshot());
            var result = V1.convertValue(stored.snapshot(), CompanyRecord.class);
            validate(result);
            var version = result.company().version().value();
            if (!stored.companyId().equals(result.company().id()) || !stored.key().tenantId().equals(result.company().tenantId())
                    || !(version == stored.expectedVersion() || (stored.expectedVersion() < Long.MAX_VALUE && version == stored.expectedVersion() + 1))) {
                throw new IllegalArgumentException();
            }
            return result;
        } catch (RuntimeException _) {
            throw new IllegalStateException("historical-update-result-invalid");
        }
    }
    private static void validateShape(Map<String, Object> document) {
        shape(document, "company", "code", "operatingName", "description", "logoUrl", "audit");
        string(document.get("code"), false); string(document.get("operatingName"), false);
        string(document.get("description"), true); string(document.get("logoUrl"), true);
        if (!(document.get("company") instanceof Map<?, ?> company)
                || !(document.get("audit") instanceof Map<?, ?> audit)) throw new IllegalArgumentException();
        shape(company, "id", "tenantId", "partyId", "status", "version");
        for (var key : new String[] {"id", "tenantId", "partyId", "status"}) string(company.get(key), false);
        if (!(company.get("version") instanceof Map<?, ?> version)) throw new IllegalArgumentException();
        shape(version, "value");
        var value = version.get("value");
        if (!(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof java.math.BigInteger)) throw new IllegalArgumentException();
        if (value instanceof java.math.BigInteger integer && integer.longValueExact() < 0) throw new IllegalArgumentException();
        shape(audit, "createdAt", "createdBy", "updatedAt", "updatedBy");
        for (var valueText : audit.values()) string(valueText, false);
    }
    private static void shape(Map<?, ?> document, String... keys) {
        if (document == null || !document.keySet().equals(java.util.Set.of(keys))) throw new IllegalArgumentException();
    }
    private static void string(Object value, boolean nullable) {
        if (!(value instanceof String) && !(nullable && value == null)) throw new IllegalArgumentException();
    }
    private static void validate(CompanyRecord row) {
        Objects.requireNonNull(row);
        var company = Objects.requireNonNull(row.company());
        if (!CompanyInformationPolicy.editable(company.status())) throw new IllegalArgumentException();
        var values = new CompanyInformation(row.code(), row.operatingName(), row.description(), row.logoUrl());
        var patch = new CompanyInformationPatch(FieldChange.present(row.code()), FieldChange.present(row.operatingName()),
                FieldChange.present(row.description()), FieldChange.present(row.logoUrl()));
        if (!values.equals(CompanyInformationPolicy.canonical(patch).apply(values))) throw new IllegalArgumentException();
        var audit = Objects.requireNonNull(row.audit());
        Objects.requireNonNull(audit.createdAt()); Objects.requireNonNull(audit.updatedAt());
        OpaqueValue.required(audit.createdBy(), 128); OpaqueValue.required(audit.updatedBy(), 128);
        if (audit.updatedAt().isBefore(audit.createdAt())
                || !audit.createdAt().equals(audit.createdAt().truncatedTo(ChronoUnit.MICROS))
                || !audit.updatedAt().equals(audit.updatedAt().truncatedTo(ChronoUnit.MICROS))) throw new IllegalArgumentException();
    }
}
