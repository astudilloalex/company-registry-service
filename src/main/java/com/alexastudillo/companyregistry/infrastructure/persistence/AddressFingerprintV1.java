package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.site.AddressMutation;
import com.alexastudillo.companyregistry.domain.model.AddressInformationPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable HU06 policy: fixed field order, original precondition, canonical decimal strings. */
public final class AddressFingerprintV1 {
    public static final short VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();
    private AddressFingerprintV1() {}

    public static byte[] bytes(AddressMutation.Command command) {
        var document = new LinkedHashMap<String, Object>();
        document.put("fingerprintVersion", VERSION);
        document.put("tenantId", command.scope().tenantId().toString());
        document.put("operation", command.intent().operation().namespace());
        document.put("companyId", command.scope().companyId().toString());
        document.put("siteId", command.scope().siteId().toString());
        document.put("addressId", command.intent().addressId() == null ? null : command.intent().addressId().toString());
        document.put("expectedVersion", Long.toString(command.expectedVersion()));
        document.put("request", request(command));
        try {
            return JSON.writeValueAsBytes(document);
        } catch (JsonProcessingException _) {
            throw new IllegalStateException("address-fingerprint-encoding-failed");
        }
    }

    private static Map<String, Object> request(AddressMutation.Command command) {
        var operation = command.intent().operation();
        if (operation == AddressMutation.Operation.DELETE) return Map.of();
        var result = new LinkedHashMap<String, Object>();
        var original = command.intent().patch();
        if (operation == AddressMutation.Operation.CREATE) {
            var value = AddressInformationPolicy.create(original);
            var text = value.text();
            result.put("addressType", value.addressType().name());
            result.put("countryCode", text.countryCode());
            result.put("addressLine1", text.addressLine1());
            result.put("administrativeDivisionCode", text.administrativeDivisionCode());
            result.put("addressLine2", text.addressLine2());
            result.put("houseNumber", text.houseNumber());
            result.put("postalCode", text.postalCode());
            result.put("latitude", decimal(value.coordinates().latitude()));
            result.put("longitude", decimal(value.coordinates().longitude()));
            result.put("isPrimary", value.isPrimary());
        } else {
            var patch = AddressInformationPolicy.canonical(original);
            var text = patch.text();
            result.put("addressType", field(patch.addressType().present(), patch.addressType().value()));
            result.put("countryCode", field(text.countryCode().present(), text.countryCode().value()));
            result.put("addressLine1", field(text.addressLine1().present(), text.addressLine1().value()));
            result.put("administrativeDivisionCode", field(text.administrativeDivisionCode().present(), text.administrativeDivisionCode().value()));
            result.put("addressLine2", field(text.addressLine2().present(), text.addressLine2().value()));
            result.put("houseNumber", field(text.houseNumber().present(), text.houseNumber().value()));
            result.put("postalCode", field(text.postalCode().present(), text.postalCode().value()));
            result.put("latitude", field(patch.coordinates().latitudePresent(), decimal(patch.coordinates().latitude())));
            result.put("longitude", field(patch.coordinates().longitudePresent(), decimal(patch.coordinates().longitude())));
            result.put("isPrimary", field(patch.isPrimary().present(), patch.isPrimary().value()));
        }
        return result;
    }

    private static Map<String, Object> field(boolean present, Object value) {
        var field = new LinkedHashMap<String, Object>();
        field.put("present", present);
        if (present) field.put("value", value);
        return field;
    }

    private static String decimal(BigDecimal number) {
        return number == null ? null : number.stripTrailingZeros().toPlainString();
    }

    public static String sign(AddressMutation.Command command, byte[] secret) {
        return HexFormat.of().formatHex(CreationFingerprintV1.mac(secret, bytes(command)));
    }

    public static boolean matches(AddressMutation.Command command, byte[] secret, String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalStateException("fingerprint-invalid");
        return MessageDigest.isEqual(CreationFingerprintV1.mac(secret, bytes(command)), HexFormat.of().parseHex(hash));
    }
}
