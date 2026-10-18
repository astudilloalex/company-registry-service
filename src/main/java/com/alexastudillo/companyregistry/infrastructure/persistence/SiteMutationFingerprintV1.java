package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.site.SiteMutation;
import com.alexastudillo.companyregistry.domain.model.SiteInformationPolicy;
import com.alexastudillo.companyregistry.domain.model.FieldChange;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable policy one; JSON object order, presence and nulls are part of the bytes. */
public final class SiteMutationFingerprintV1 {
    public static final short VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();
    private SiteMutationFingerprintV1() {}

    public static byte[] bytes(SiteMutation.Command command) {
        var input = SiteInformationPolicy.canonical(command.patch());
        var patch = new LinkedHashMap<String, Object>();
        patch.put("code", field(input.code()));
        patch.put("name", field(input.name()));
        patch.put("siteType", field(input.siteType()));
        var document = new LinkedHashMap<String, Object>();
        document.put("fingerprintVersion", VERSION);
        document.put("tenantId", command.context().tenantId().toString());
        document.put("operation", command.operation().namespace());
        document.put("companyId", command.companyId().toString());
        document.put("siteId", command.siteId().toString());
        document.put("expectedVersion", Long.toString(command.expectedVersion()));
        document.put("request", command.operation() == SiteMutation.Operation.UPDATE ? patch : Map.of());
        try {
            return JSON.writeValueAsBytes(document);
        } catch (JsonProcessingException _) {
            throw new IllegalStateException("update-fingerprint-encoding-failed");
        }
    }

    private static Map<String, Object> field(FieldChange field) {
        var document = new LinkedHashMap<String, Object>();
        document.put("present", field.present());
        if (field.present()) document.put("value", field.value());
        return document;
    }

    public static String sign(SiteMutation.Command command, byte[] secret) {
        return HexFormat.of().formatHex(CreationFingerprintV1.mac(secret, bytes(command)));
    }

    public static boolean matches(SiteMutation.Command command, byte[] secret, String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalStateException("fingerprint-invalid");
        return MessageDigest.isEqual(CreationFingerprintV1.mac(secret, bytes(command)), HexFormat.of().parseHex(hash));
    }
}
