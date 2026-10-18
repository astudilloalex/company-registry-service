package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;

/** Operational information owned by the Site aggregate. */
public record SiteInformation(String code, String name, SiteType siteType) {
    public SiteInformation {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(siteType, "siteType");
    }
}
