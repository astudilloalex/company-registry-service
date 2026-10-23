package com.alexastudillo.companyregistry.domain.model;

import com.alexastudillo.companyregistry.domain.text.ContactValue;
import com.alexastudillo.companyregistry.domain.text.InvalidText;
import java.util.Objects;

/** Canonical intention independent of any current contact or telephone channel. */
public final class ContactIntentPolicyV1 {
    private ContactIntentPolicyV1() {}

    public enum Family { EMAIL, PHONE }

    public record Intent(ContactInformationPatch patch, Family valueFamily) {}

    public static Intent canonical(ContactInformationPatch input) {
        Objects.requireNonNull(input, "input");
        var type = input.contactType().present()
                ? FieldChange.present(ContactInformationPolicy.type(input.contactType().value()).name())
                : FieldChange.absent();
        Family family = null;
        var value = input.value();
        if (value.present()) {
            family = family(value.value());
            value = FieldChange.present(ContactValue.normalize(
                    family == Family.EMAIL ? ContactType.EMAIL : ContactType.PHONE, value.value()));
        }
        var label = input.label().present()
                ? FieldChange.present(ContactInformationPolicy.label(input.label().value())) : FieldChange.absent();
        if (input.isPrimary().present() && input.isPrimary().value() == null) {
            throw new InvalidText("contact-is-primary-must-be-boolean");
        }
        return new Intent(new ContactInformationPatch(type, value, label, input.isPrimary()), family);
    }

    private static Family family(String value) {
        if (value == null || value.isBlank()) throw new InvalidText("contact-value-required");
        if (value.indexOf('@') >= 0) return Family.EMAIL;
        if (value.strip().startsWith("+")) return Family.PHONE;
        throw new InvalidText("contact-value-invalid");
    }
}
