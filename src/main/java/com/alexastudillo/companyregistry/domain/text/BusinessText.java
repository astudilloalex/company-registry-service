package com.alexastudillo.companyregistry.domain.text;

import java.util.Locale;

/** Apply only to business text, never to contacts or opaque protocol values. */
public final class BusinessText {
    private BusinessText() {
    }

    public static String required(String input, int maximumLength) {
        if (input == null) {
            throw new InvalidText("text-required");
        }
        return validate(input.strip().toUpperCase(Locale.ROOT), maximumLength);
    }

    public static String optional(String input, int maximumLength) {
        return input == null ? null : required(input, maximumLength);
    }

    private static String validate(String canonical, int maximumLength) {
        if (maximumLength < 1) {
            throw new IllegalArgumentException("maximum-length-invalid");
        }
        if (canonical.isBlank()) {
            throw new InvalidText("text-blank");
        }
        if (canonical.codePointCount(0, canonical.length()) > maximumLength) {
            throw new InvalidText("text-too-long");
        }
        return canonical;
    }
}
