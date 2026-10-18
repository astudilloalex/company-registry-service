package com.alexastudillo.companyregistry.domain.model;

import com.alexastudillo.companyregistry.domain.text.BusinessText;
import com.alexastudillo.companyregistry.domain.text.InvalidText;
import java.util.Objects;

/** Version one information rules; omission never applies registration defaults. */
public final class SiteInformationPolicy {
    private SiteInformationPolicy() {}

    public record Decision(SiteInformation information, boolean changed) {}

    public static SiteInformationPatch canonical(SiteInformationPatch patch) {
        Objects.requireNonNull(patch, "patch");
        var code = text(patch.code(), 64, "site-code");
        var name = text(patch.name(), 200, "site-name");
        var type = patch.siteType().present()
                ? FieldChange.present(SiteRegistrationPolicy.type(patch.siteType()).name()) : FieldChange.absent();
        return new SiteInformationPatch(code, name, type);
    }

    public static Decision decide(SiteInformation previous, SiteInformationPatch patch) {
        Objects.requireNonNull(previous, "previous");
        var canonical = canonical(patch);
        var effective = new SiteInformation(canonical.code().apply(previous.code()),
                canonical.name().apply(previous.name()), canonical.siteType().present()
                        ? SiteType.valueOf(canonical.siteType().value()) : previous.siteType());
        return new Decision(effective, !effective.equals(previous));
    }

    private static FieldChange text(FieldChange field, int limit, String name) {
        if (!field.present()) return FieldChange.absent();
        try {
            return FieldChange.present(BusinessText.required(field.value(), limit));
        } catch (InvalidText failure) {
            throw new InvalidText(name + ("text-too-long".equals(failure.rule()) ? "-too-long" : "-required"));
        }
    }
}
