package com.alexastudillo.companyregistry.infrastructure.persistence;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable secret material; never expose it via a record, toString or
 * diagnostic.
 */
public final class HmacKeys {
    private final String activeId;
    private final Map<String, byte[]> keys;

    public HmacKeys(String activeId, Map<String, String> encoded) {
        if (!validId(activeId) || encoded == null || encoded.isEmpty())
            throw invalid();
        var decoded = new HashMap<String, byte[]>();
        for (var entry : encoded.entrySet()) {
            if (!validId(entry.getKey()) || entry.getValue() == null)
                throw invalid();
            byte[] secret;
            try {
                secret = Base64.getDecoder().decode(entry.getValue());
            } catch (IllegalArgumentException _) {
                throw invalid();
            }
            if (secret.length < 32)
                throw invalid();
            decoded.put(entry.getKey(), secret);
        }
        if (!decoded.containsKey(activeId))
            throw invalid();
        this.activeId = activeId;
        this.keys = Map.copyOf(decoded);
    }

    public String activeId() {
        return activeId;
    }

    public byte[] secret(String id) {
        var secret = keys.get(id);
        if (secret == null)
            throw new IllegalStateException("historical-key-unavailable");
        return secret.clone();
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("idempotency-keyring-invalid");
    }
}
