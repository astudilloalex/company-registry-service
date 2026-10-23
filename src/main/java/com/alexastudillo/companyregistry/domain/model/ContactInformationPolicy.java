package com.alexastudillo.companyregistry.domain.model;

import com.alexastudillo.companyregistry.domain.text.BusinessText;
import com.alexastudillo.companyregistry.domain.text.ContactValue;
import com.alexastudillo.companyregistry.domain.text.InvalidText;
import java.util.Objects;

/** Effective validation happens after ownership, revision and editability admission. */
public final class ContactInformationPolicy {
    private ContactInformationPolicy() {}

    public record Decision(ContactInformation information, boolean changed) {}

    public static ContactInformation create(ContactInformationPatch input) {
        var required = new ContactInformationPatch(FieldChange.present(input.contactType().value()),
                FieldChange.present(input.value().value()), input.label(), input.isPrimary());
        return apply(null, ContactIntentPolicyV1.canonical(required).patch());
    }

    public static Decision decide(ContactInformation previous, ContactInformationPatch input) {
        Objects.requireNonNull(previous, "previous");
        var effective = apply(previous, ContactIntentPolicyV1.canonical(input).patch());
        return new Decision(effective, !previous.equals(effective));
    }

    private static ContactInformation apply(ContactInformation previous, ContactInformationPatch patch) {
        var type = patch.contactType().present() ? ContactType.valueOf(patch.contactType().value())
                : Objects.requireNonNull(previous).contactType();
        String value = patch.value().apply(previous == null ? null : previous.value());
        String label = patch.label().apply(previous == null ? null : previous.label());
        boolean primary = patch.isPrimary().present() ? Boolean.TRUE.equals(patch.isPrimary().value())
                : previous != null && previous.isPrimary();
        return new ContactInformation(type, ContactValue.normalize(type, value), label, primary);
    }

    public static ContactType type(String value) {
        String canonical = BusinessText.canonical(value);
        if (canonical == null || canonical.isBlank()) throw new InvalidText("contact-type-required");
        try {
            return ContactType.valueOf(canonical);
        } catch (IllegalArgumentException _) {
            throw new InvalidText("contact-type-not-supported");
        }
    }

    public static String label(String value) {
        try {
            return BusinessText.optional(value, 80);
        } catch (InvalidText failure) {
            throw new InvalidText("text-too-long".equals(failure.rule())
                    ? "contact-label-too-long" : "contact-label-blank");
        }
    }
}
