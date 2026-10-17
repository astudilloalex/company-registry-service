package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.company.*;
import com.fasterxml.jackson.databind.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class AuthenticatedCompanyCursors implements CompanyCursors {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORDER = "created_at DESC,id DESC";
    private static final String VERSION = "version";
    private final HmacKeys keys;

    @Inject
    public AuthenticatedCompanyCursors(FingerprintConfiguration configuration) {
        this(new HmacKeys(configuration.activeKeyId(), configuration.keys()));
    }

    public AuthenticatedCompanyCursors(HmacKeys keys) {
        this.keys = keys;
    }

    @Override
    public String encode(UUID tenant, CompanySelection.Filters filters, CompanySelection.Boundary boundary) {
        var body = new LinkedHashMap<String, Object>();
        body.put(VERSION, 1);
        body.put("keyId", keys.activeId());
        body.put("tenantId", tenant.toString());
        body.put("code", filters.code());
        body.put("partyId", filters.partyId() == null ? null : filters.partyId().toString());
        body.put("status", filters.status() == null ? null : filters.status().name());
        body.put("order", ORDER);
        body.put("createdAt", boundary.createdAt().toString());
        body.put("id", boundary.id().toString());
        try {
            var payload = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(body));
            return payload + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(keys.activeId(), payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException _) {
            throw new IllegalStateException("cursor-encoding-failed");
        }
    }

    @Override
    public CompanySelection.Boundary decode(String cursor, UUID tenant, CompanySelection.Filters filters) {
        try {
            if (cursor == null || cursor.length() > 4096)
                throw new IllegalArgumentException();
            var parts = cursor.split("\\.", -1);
            if (parts.length != 2)
                throw new IllegalArgumentException();
            var body = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!body.isObject() || body.size() != 9 || !body.path(VERSION).isIntegralNumber()
                    || body.path(VERSION).asInt() != 1)
                throw new IllegalArgumentException();
            var keyId = body.path("keyId").textValue();
            if (keyId == null || !MessageDigest.isEqual(mac(keyId, parts[0]), Base64.getUrlDecoder().decode(parts[1])))
                throw new IllegalArgumentException();
            if (!tenant.toString().equals(body.path("tenantId").textValue())
                    || !ORDER.equals(body.path("order").textValue())
                    || !Objects.equals(filters.code(), nullable(body, "code"))
                    || !Objects.equals(filters.partyId() == null ? null : filters.partyId().toString(),
                            nullable(body, "partyId"))
                    || !Objects.equals(filters.status() == null ? null : filters.status().name(),
                            nullable(body, "status")))
                throw new IllegalArgumentException();
            return new CompanySelection.Boundary(Instant.parse(body.path("createdAt").textValue()),
                    UUID.fromString(body.path("id").textValue()));
        } catch (Exception _) {
            throw new CompanyFailure(CompanyFailure.Kind.INVALID_INPUT);
        }
    }

    private static String nullable(JsonNode body, String field) {
        var value = body.get(field);
        if (value == null || !(value.isNull() || value.isTextual()))
            throw new IllegalArgumentException();
        return value.textValue();
    }

    private byte[] mac(String keyId, String payload) {
        return CreationFingerprintV1.mac(keys.secret(keyId),
                ("company.cursor.v1\u0000" + payload).getBytes(StandardCharsets.UTF_8));
    }
}
