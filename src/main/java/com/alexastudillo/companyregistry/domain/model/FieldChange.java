package com.alexastudillo.companyregistry.domain.model;

/** Presence is independent of the value: a present null requests a clear. */
public record FieldChange(boolean present, String value) {
    public FieldChange {
        if (!present && value != null) {
            throw new IllegalArgumentException("absent-field-has-value");
        }
    }

    public static FieldChange absent() {
        return new FieldChange(false, null);
    }

    public static FieldChange present(String value) {
        return new FieldChange(true, value);
    }

    public String apply(String previous) {
        return present ? value : previous;
    }
}
