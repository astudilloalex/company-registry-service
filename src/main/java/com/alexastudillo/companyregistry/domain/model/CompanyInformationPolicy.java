package com.alexastudillo.companyregistry.domain.model;

import com.alexastudillo.companyregistry.domain.text.BusinessText;

/** Version one operational policy; canonicalize only the fields supplied. */
public final class CompanyInformationPolicy {
    private CompanyInformationPolicy() {
    }

    public record Decision(CompanyInformation information, boolean changed) {
    }

    public static boolean editable(CompanyStatus status) {
        return status == CompanyStatus.DRAFT || status == CompanyStatus.ACTIVE || status == CompanyStatus.INACTIVE;
    }

    public static Decision decide(CompanyStatus status, CompanyInformation previous, CompanyInformationPatch patch) {
        if (!editable(status)) throw new IllegalStateException("company-information-not-editable");
        var effective = canonical(patch).apply(previous);
        return new Decision(effective, !effective.equals(previous));
    }

    public static CompanyInformationPatch canonical(CompanyInformationPatch patch) {
        return new CompanyInformationPatch(text(patch.code(), 64, true), text(patch.operatingName(), 200, true),
                text(patch.description(), 500, false), patch.logoUrl().present()
                        ? FieldChange.present(RegistrationPolicy.logo(patch.logoUrl().value())) : FieldChange.absent());
    }

    private static FieldChange text(FieldChange field, int limit, boolean required) {
        if (!field.present()) return FieldChange.absent();
        return FieldChange.present(required ? BusinessText.required(field.value(), limit)
                : BusinessText.optional(field.value(), limit));
    }
}
