package com.alexastudillo.companyregistry.domain.text;

/** Validate presence/length without canonicalizing protocol identifiers or case-sensitive values. */
public final class OpaqueValue {
    private OpaqueValue() {
    }

    public static String required(String input, int maximumLength) {
        requireValid(input, maximumLength);
        return input;
    }

    /** Validate an already-held value when no converted value is needed. */
    public static void requireValid(String input, int maximumLength) {
        if (maximumLength < 1) {
            throw new IllegalArgumentException("maximum-length-invalid");
        }
        if (input == null || input.isBlank()) {
            throw new InvalidText("opaque-value-required");
        }
        if (input.codePointCount(0, input.length()) > maximumLength) {
            throw new InvalidText("opaque-value-too-long");
        }
    }
}
