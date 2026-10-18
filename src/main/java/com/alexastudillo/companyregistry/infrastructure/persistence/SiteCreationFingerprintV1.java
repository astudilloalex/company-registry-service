package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.site.SiteRegistration;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;

/** Immutable historical encoding. Do not change policy one when adding a new policy. */
public final class SiteCreationFingerprintV1 {
    public static final short VERSION = 1;
    public static final String OPERATION = SiteRegistration.OPERATION;
    private static final ObjectMapper JSON = new ObjectMapper();
    private SiteCreationFingerprintV1() {}

    public static byte[] bytes(SiteRegistration.Command command) {
        var input = command.effective();
        var document = new LinkedHashMap<String, Object>();
        document.put("fingerprintVersion", VERSION);
        document.put("tenantId", command.context().tenantId().toString());
        document.put("operation", OPERATION);
        document.put("companyId", command.companyId().toString());
        document.put("code", input.code());
        document.put("name", input.name());
        document.put("siteType", input.siteType().name());
        try {
            return JSON.writeValueAsBytes(document);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("fingerprint-encoding-failed", failure);
        }
    }

    public static String sign(SiteRegistration.Command command, byte[] secret) {
        return HexFormat.of().formatHex(CreationFingerprintV1.mac(secret, bytes(command)));
    }

    public static boolean matches(SiteRegistration.Command command, byte[] secret, String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalStateException("fingerprint-invalid");
        return MessageDigest.isEqual(CreationFingerprintV1.mac(secret, bytes(command)), HexFormat.of().parseHex(hash));
    }

}
