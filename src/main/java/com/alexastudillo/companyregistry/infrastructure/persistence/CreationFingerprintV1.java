package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.company.CompanyRegistration;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Immutable historical encoding. Do not change policy one when adding a new policy. */
public final class CreationFingerprintV1 {
    public static final short VERSION = 1;
    public static final String OPERATION = "company.create.v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private CreationFingerprintV1() {}

    public static byte[] bytes(CompanyRegistration.Command command) {
        var input = command.effective();
        var request = new LinkedHashMap<String, Object>();
        request.put("partyId", input.partyId().toString());
        request.put("code", input.code());
        request.put("operatingName", input.operatingName());
        request.put("description", input.description());
        request.put("logoUrl", input.logoUrl());
        var document = new LinkedHashMap<String, Object>();
        document.put("fingerprintVersion", VERSION);
        document.put("tenantId", command.context().tenantId().toString());
        document.put("operation", OPERATION);
        document.put("request", request);
        try {
            return JSON.writeValueAsBytes(document);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("fingerprint-encoding-failed", failure);
        }
    }

    public static String sign(CompanyRegistration.Command command, byte[] secret) {
        return HexFormat.of().formatHex(mac(secret, bytes(command)));
    }

    public static boolean matches(CompanyRegistration.Command command, byte[] secret, String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalStateException("fingerprint-invalid");
        return MessageDigest.isEqual(mac(secret, bytes(command)), HexFormat.of().parseHex(hash));
    }

    static byte[] mac(byte[] secret, byte[] message) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("fingerprint-signing-failed", failure);
        }
    }
}
