package com.alexastudillo.companyregistry.domain.text;

import com.alexastudillo.companyregistry.domain.model.ContactType;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.util.Locale;
import java.util.Objects;

/**
 * Contact-specific policy: never invoke the generic business-uppercase policy.
 */
public final class ContactValue {
    private static final String INVALID_VALUE = "contact-value-invalid";
    private ContactValue() {
    }

    public static String normalize(ContactType type, String input) {
        Objects.requireNonNull(type, "type");
        if (input == null || input.isBlank()) {
            throw new InvalidText("contact-value-required");
        }
        String value = input.strip();
        return type == ContactType.EMAIL ? email(value) : phone(type, value);
    }

    private static String email(String value) {
        if (value.chars().anyMatch(character -> character > 127)) {
            throw new InvalidText(INVALID_VALUE);
        }
        String canonical = value.toLowerCase(Locale.ROOT);
        int separator = canonical.indexOf('@');
        if (canonical.length() > 254 || separator > 64) {
            throw new InvalidText("contact-value-too-long");
        }
        if (separator < 1 || separator != canonical.lastIndexOf('@')) {
            throw new InvalidText(INVALID_VALUE);
        }
        String local = canonical.substring(0, separator);
        String domain = canonical.substring(separator + 1);
        if (!local.matches("[a-z0-9!#$%&'*+/=?^_`{|}~-]++(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]++)*+")
                || !domain.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)++")) {
            throw new InvalidText(INVALID_VALUE);
        }
        return canonical;
    }

    private static String phone(ContactType type, String value) {
        // Never infer a country from locale or tenant.
        if (!value.matches("\\+[0-9 ()-]+")) {
            throw new InvalidText(INVALID_VALUE);
        }
        var numbers = PhoneNumberUtil.getInstance();
        try {
            var number = numbers.parse(value, "ZZ");
            if (number.hasExtension() || !numbers.isValidNumber(number)) {
                throw new InvalidText(INVALID_VALUE);
            }
            var classification = numbers.getNumberType(number);
            if (type == ContactType.MOBILE && classification != PhoneNumberUtil.PhoneNumberType.MOBILE
                    && classification != PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE) {
                throw new InvalidText("contact-mobile-number-required");
            }
            String canonical = numbers.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
            if (!canonical.matches("\\+\\d{1,15}")) {
                throw new InvalidText(INVALID_VALUE);
            }
            return canonical;
        } catch (NumberParseException _) {
            throw new InvalidText(INVALID_VALUE);
        }
    }
}
