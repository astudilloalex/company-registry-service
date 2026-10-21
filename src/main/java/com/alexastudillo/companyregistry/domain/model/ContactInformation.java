package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;

/** Contact values owned and versioned by Site; identity and audit are separate. */
public record ContactInformation(ContactType contactType, String value, String label, boolean isPrimary) {
    public ContactInformation {
        Objects.requireNonNull(contactType, "contactType");
        Objects.requireNonNull(value, "value");
    }
}
