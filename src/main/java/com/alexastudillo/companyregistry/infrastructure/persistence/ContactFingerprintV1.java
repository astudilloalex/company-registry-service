package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.site.ContactMutation;
import com.alexastudillo.companyregistry.domain.model.ContactInformationPatch;
import com.alexastudillo.companyregistry.domain.model.FieldChange;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alexastudillo.companyregistry.domain.model.ContactIntentPolicyV1;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable HU07 policy: fixed field order, original precondition, canonical decimal strings. */
public final class ContactFingerprintV1 {
    public static final short VERSION = 1;
    private static final String VALUE = "value";
    private static final ObjectMapper JSON = new ObjectMapper();
    private ContactFingerprintV1() {}

    public static byte[] bytes(ContactMutation.Command command) {
        var document = new LinkedHashMap<String, Object>();
        document.put("fingerprintVersion", VERSION);
        document.put("tenantId", command.scope().tenantId().toString());
        document.put("operation", command.intent().operation().namespace());
        document.put("companyId", command.scope().companyId().toString());
        document.put("siteId", command.scope().siteId().toString());
        document.put("contactId", command.intent().contactId() == null ? null : command.intent().contactId().toString());
        document.put("expectedVersion", Long.toString(command.expectedVersion()));
        document.put("request", request(command));
        try {
            return JSON.writeValueAsBytes(document);
        } catch (JsonProcessingException _) {
            throw new IllegalStateException("contact-fingerprint-encoding-failed");
        }
    }

    private static Map<String, Object> request(ContactMutation.Command command) {
        var operation = command.intent().operation();
        if (operation == ContactMutation.Operation.DELETE) return Map.of();
        var result = new LinkedHashMap<String, Object>();
        var original = command.intent().patch();
        if (operation == ContactMutation.Operation.CREATE) {
            // Sign supplied lexical intent; channel compatibility belongs to fresh protected acceptance.
            var required = new ContactInformationPatch(FieldChange.present(original.contactType().value()),
                    FieldChange.present(original.value().value()), original.label(), original.isPrimary());
            var intent = ContactIntentPolicyV1.canonical(required);
            var patch = intent.patch();
            result.put("contactType", patch.contactType().value());
            result.put("valueFamily", intent.valueFamily().name());
            result.put(VALUE, patch.value().value());
            result.put("label", patch.label().present() ? patch.label().value() : null);
            result.put("isPrimary", patch.isPrimary().present() && Boolean.TRUE.equals(patch.isPrimary().value()));
        } else {
            var intent = ContactIntentPolicyV1.canonical(original);
            var patch = intent.patch();
            result.put("contactType", field(patch.contactType().present(), patch.contactType().value()));
            result.put(VALUE, field(patch.value().present(), patch.value().value()));
            result.put("valueFamily", intent.valueFamily() == null ? null : intent.valueFamily().name());
            result.put("label", field(patch.label().present(), patch.label().value()));
            result.put("isPrimary", field(patch.isPrimary().present(), patch.isPrimary().value()));
        }
        return result;
    }

    private static Map<String, Object> field(boolean present, Object value) {
        var field = new LinkedHashMap<String, Object>();
        field.put("present", present);
        if (present) field.put(VALUE, value);
        return field;
    }

    public static String sign(ContactMutation.Command command, byte[] secret) {
        return HexFormat.of().formatHex(CreationFingerprintV1.mac(secret, bytes(command)));
    }

    public static boolean matches(ContactMutation.Command command, byte[] secret, String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalStateException("fingerprint-invalid");
        return MessageDigest.isEqual(CreationFingerprintV1.mac(secret, bytes(command)), HexFormat.of().parseHex(hash));
    }
}
