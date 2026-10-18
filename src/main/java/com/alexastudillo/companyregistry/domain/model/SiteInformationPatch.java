package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;

/** Original partial intent, retained until the current or historical policy is selected. */
public record SiteInformationPatch(FieldChange code, FieldChange name, FieldChange siteType) {
    public SiteInformationPatch {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(siteType, "siteType");
    }

    public static SiteInformationPatch empty() {
        return new SiteInformationPatch(FieldChange.absent(), FieldChange.absent(), FieldChange.absent());
    }
}
