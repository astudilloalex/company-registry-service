package com.alexastudillo.companyregistry.domain.model;

import com.alexastudillo.companyregistry.domain.text.BusinessText;
import com.alexastudillo.companyregistry.domain.text.InvalidText;
import java.util.Objects;

/** Version one Site registration rules, also retained for historical fingerprints. */
public final class SiteRegistrationPolicy {
    private SiteRegistrationPolicy() {}

    public record Input(String code, String name, SiteType siteType) {}

    public static Input canonical(String code, String name, FieldChange siteType) {
        // Field precedence is independent of the order of the original properties.
        var effectiveCode = code(code);
        var effectiveName = text(name, 200, "site-name");
        Objects.requireNonNull(siteType, "siteType");
        return new Input(effectiveCode, effectiveName, type(siteType));
    }

    public static String code(String value) {
        return text(value, 64, "site-code");
    }

    public static SiteType type(FieldChange field) {
        Objects.requireNonNull(field, "siteType");
        if (!field.present()) return SiteType.BRANCH;
        if (field.value() == null) throw new InvalidText("site-type-null-not-allowed");
        try {
            return SiteType.valueOf(field.value());
        } catch (IllegalArgumentException _) {
            throw new InvalidText("site-type-not-supported");
        }
    }

    public static boolean admits(CompanyStatus status) {
        return status == CompanyStatus.DRAFT || status == CompanyStatus.ACTIVE || status == CompanyStatus.INACTIVE;
    }

    private static String text(String value, int limit, String field) {
        try {
            return BusinessText.required(value, limit);
        } catch (InvalidText failure) {
            throw new InvalidText(field + ("text-too-long".equals(failure.rule()) ? "-too-long" : "-required"));
        }
    }
}
