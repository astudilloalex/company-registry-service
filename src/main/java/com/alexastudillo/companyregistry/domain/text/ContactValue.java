package com.alexastudillo.companyregistry.domain.text;

import com.alexastudillo.companyregistry.domain.model.ContactType;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.util.Objects;

/**
 * Contact-specific policy: never invoke the generic business-uppercase policy.
 */
public final class ContactValue {
    private ContactValue() {
    }

    public static String normalize(ContactType type, String input) {
        Objects.requireNonNull(type, "type");
        if (input == null || input.isBlank()) {
            throw new InvalidText("contact-value-required");
        }
        String value = input.strip();
        return type == ContactType.EMAIL ? email(value) : phone(value);
    }

    private static String email(String value) {
        // Preserve both local-part and domain case. Accept ordinary unquoted mailboxes.
        if (value.length() > 320 || !value.matches("[^\\s@]+@[^\\s@.]+\\.[^\\s@]+")
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidText("email-invalid");
        }
        return value;
    }

    private static String phone(String value) {
        // Never infer a country from locale or tenant.
        if (!value.matches("\\+[0-9 ()-]+")) {
            throw new InvalidText("phone-invalid");
        }
        var numbers = PhoneNumberUtil.getInstance();
        try {
            var number = numbers.parse(value, "ZZ");
            if (number.hasExtension() || !numbers.isValidNumber(number)) {
                throw new InvalidText("phone-invalid");
            }
            return numbers.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException _) {
            throw new InvalidText("phone-invalid");
        }
    }
}
