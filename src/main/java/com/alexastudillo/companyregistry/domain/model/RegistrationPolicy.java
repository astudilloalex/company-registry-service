package com.alexastudillo.companyregistry.domain.model;

import com.alexastudillo.companyregistry.domain.text.BusinessText;
import com.alexastudillo.companyregistry.domain.text.InvalidText;
import com.alexastudillo.companyregistry.domain.text.OpaqueValue;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * Version one rules for the effective creation input; no transport or provider
 * I/O.
 */
public final class RegistrationPolicy {
    private RegistrationPolicy() {
    }

    public record Input(UUID partyId, String code, String operatingName, String description, String logoUrl) {
    }

    public static Input canonical(UUID partyId, String code, String operatingName, String description, String logoUrl) {
        Objects.requireNonNull(partyId, "party-id-required");
        return new Input(partyId, BusinessText.required(code, 64), BusinessText.required(operatingName, 200),
                BusinessText.optional(description, 500), logo(logoUrl));
    }

    public static boolean eligible(String kind, String state) {
        return "LEGAL_ENTITY".equals(kind)
                && ("DRAFT".equals(state) || "ACTIVE".equals(state) || "INACTIVE".equals(state));
    }

    private static String logo(String value) {
        if (value == null)
            return null;
        OpaqueValue.required(value, 1000);
        try {
            var uri = URI.create(value);
            if (!value.equals(value.strip()) || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException _) {
            throw new InvalidText("logo-url-invalid");
        }
        return value;
    }
}
