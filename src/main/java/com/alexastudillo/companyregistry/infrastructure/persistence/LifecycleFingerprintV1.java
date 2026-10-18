package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.company.CompanyLifecycle;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable policy one; JSON object order, presence and nulls are part of the bytes. */
public final class LifecycleFingerprintV1 {
    public static final short VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();
    private LifecycleFingerprintV1() {}

    public static byte[] bytes(CompanyLifecycle.Command command) {
        var document = new LinkedHashMap<String, Object>();
        document.put("fingerprintVersion", VERSION);
        document.put("tenantId", command.context().tenantId().toString());
        document.put("operation", command.operation().namespace());
        document.put("companyId", command.companyId().toString());
        document.put("expectedVersion", Long.toString(command.expectedVersion()));
        document.put("request", Map.of());
        try {
            return JSON.writeValueAsBytes(document);
        } catch (JsonProcessingException _) {
            throw new IllegalStateException("lifecycle-fingerprint-encoding-failed");
        }
    }

    public static String sign(CompanyLifecycle.Command command, byte[] secret) {
        return HexFormat.of().formatHex(CreationFingerprintV1.mac(secret, bytes(command)));
    }

    public static boolean matches(CompanyLifecycle.Command command, byte[] secret, String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalStateException("fingerprint-invalid");
        return MessageDigest.isEqual(CreationFingerprintV1.mac(secret, bytes(command)), HexFormat.of().parseHex(hash));
    }
}
