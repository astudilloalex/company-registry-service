package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.site.*;
import com.fasterxml.jackson.databind.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class AuthenticatedSiteCursors implements SiteCursors {
    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final String ORDER = "created_at DESC,id DESC";
    private static final String VERSION = "version";
    private static final String KEY_ID = "keyId";
    private static final String TENANT_ID = "tenantId";
    private static final String COMPANY_ID = "companyId";
    private static final String CODE = "code";
    private static final String SITE_TYPE = "siteType";
    private static final String STATUS = "status";
    private static final String ORDER_FIELD = "order";
    private static final String CREATED_AT = "createdAt";
    private static final String ID = "id";
    private static final Set<String> EXPECTED_FIELDS = Set.of(
            VERSION, KEY_ID, TENANT_ID, COMPANY_ID, CODE, SITE_TYPE, STATUS, ORDER_FIELD, CREATED_AT, ID);
    private final HmacKeys keys;

    @Inject
    public AuthenticatedSiteCursors(FingerprintConfiguration configuration) {
        this(new HmacKeys(configuration.activeKeyId(), configuration.keys()));
    }

    public AuthenticatedSiteCursors(HmacKeys keys) {
        this.keys = keys;
    }

    @Override
    public String encode(UUID tenant, UUID company, SiteSelection.Filters filters, SiteSelection.Boundary boundary) {
        var body = new LinkedHashMap<String, Object>();
        body.put(VERSION, 1);
        body.put(KEY_ID, keys.activeId());
        body.put(TENANT_ID, tenant.toString());
        body.put(COMPANY_ID, company.toString());
        body.put(CODE, filters.code());
        body.put(SITE_TYPE, filters.siteType() == null ? null : filters.siteType().name());
        body.put(STATUS, filters.status() == null ? null : filters.status().name());
        body.put(ORDER_FIELD, ORDER);
        body.put(CREATED_AT, boundary.createdAt().toString());
        body.put(ID, boundary.id().toString());
        try {
            var payload = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(body));
            return payload + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(keys.activeId(), payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException _) {
            throw new IllegalStateException("cursor-encoding-failed");
        }
    }

    @Override
    public SiteSelection.Boundary decode(String cursor, UUID tenant, UUID company, SiteSelection.Filters filters) {
        try {
            if (cursor == null || cursor.length() > 4096)
                throw new IllegalArgumentException();
            var parts = cursor.split("\\.", -1);
            if (parts.length != 2)
                throw new IllegalArgumentException();
            var body = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!body.isObject() || body.size() != 10 || !body.path(VERSION).isIntegralNumber()
                    || !body.path(VERSION).canConvertToInt() || body.path(VERSION).intValue() != 1)
                throw new IllegalArgumentException();
            var fields = new HashSet<String>();
            body.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(EXPECTED_FIELDS)) throw new IllegalArgumentException();
            var keyId = body.path(KEY_ID).textValue();
            if (keyId == null || !MessageDigest.isEqual(mac(keyId, parts[0]), Base64.getUrlDecoder().decode(parts[1])))
                throw new IllegalArgumentException();
            if (!tenant.toString().equals(body.path(TENANT_ID).textValue())
                    || !company.toString().equals(body.path(COMPANY_ID).textValue())
                    || !ORDER.equals(body.path(ORDER_FIELD).textValue())
                    || !Objects.equals(filters.code(), nullable(body, CODE))
                    || !Objects.equals(filters.siteType() == null ? null : filters.siteType().name(),
                            nullable(body, SITE_TYPE))
                    || !Objects.equals(filters.status() == null ? null : filters.status().name(),
                            nullable(body, STATUS)))
                throw new IllegalArgumentException();
            return new SiteSelection.Boundary(Instant.parse(body.path(CREATED_AT).textValue()),
                    UUID.fromString(body.path(ID).textValue()));
        } catch (Exception _) {
            throw new SiteFailure(SiteFailure.Kind.CURSOR_INVALID);
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
                ("company.site.cursor.v1\u0000" + payload).getBytes(StandardCharsets.UTF_8));
    }
}
