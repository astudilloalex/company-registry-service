package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;

/** Raw text and independent presence survive until selection of the intention policy. */
public record ContactInformationPatch(FieldChange contactType, FieldChange value, FieldChange label,
        PrimaryChange isPrimary) {
    public ContactInformationPatch {
        Objects.requireNonNull(contactType, "contactType");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(isPrimary, "isPrimary");
    }

    public static ContactInformationPatch empty() {
        return new ContactInformationPatch(FieldChange.absent(), FieldChange.absent(), FieldChange.absent(),
                PrimaryChange.absent());
    }

    public record PrimaryChange(boolean present, Boolean value) {
        public PrimaryChange {
            if (!present && value != null) throw new IllegalArgumentException("absent-primary-has-value");
        }

        public static PrimaryChange absent() {
            return new PrimaryChange(false, null);
        }
    }
}
