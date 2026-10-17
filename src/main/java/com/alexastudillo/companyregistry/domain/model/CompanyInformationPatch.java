package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;

/** Original structured intent, retained until the applicable policy is selected. */
public record CompanyInformationPatch(FieldChange code, FieldChange operatingName,
        FieldChange description, FieldChange logoUrl) {
    public CompanyInformationPatch {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(operatingName, "operatingName");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(logoUrl, "logoUrl");
    }

    public static CompanyInformationPatch empty() {
        return new CompanyInformationPatch(FieldChange.absent(), FieldChange.absent(),
                FieldChange.absent(), FieldChange.absent());
    }

    public CompanyInformation apply(CompanyInformation previous) {
        if ((code.present() && code.value() == null)
                || (operatingName.present() && operatingName.value() == null)) {
            throw new IllegalArgumentException("required-field-cannot-be-cleared");
        }
        return new CompanyInformation(code.apply(previous.code()), operatingName.apply(previous.operatingName()),
                description.apply(previous.description()), logoUrl.apply(previous.logoUrl()));
    }
}
