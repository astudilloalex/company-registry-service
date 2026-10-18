package com.alexastudillo.companyregistry.domain.model;

import com.alexastudillo.companyregistry.domain.text.InvalidText;
import java.util.Objects;

/** HU06 version one canonical intention and effective-change policy. */
public final class AddressInformationPolicy {
    private AddressInformationPolicy() {}

    public record Decision(AddressInformation information, boolean changed) {}

    public static AddressInformationPatch canonical(AddressInformationPatch patch) {
        Objects.requireNonNull(patch, "patch");
        var type = patch.addressType().present()
                ? FieldChange.present(AddressText.type(patch.addressType().value()).name()) : FieldChange.absent();
        var text = patch.text();
        var country = text.countryCode().present()
                ? FieldChange.present(AddressText.country(text.countryCode().value())) : FieldChange.absent();
        var line1 = text(text.addressLine1(), 300, "address-line1", true);
        var division = text(text.administrativeDivisionCode(), 64, "address-administrative-division-code", false);
        var line2 = text(text.addressLine2(), 300, "address-line2", false);
        var house = text(text.houseNumber(), 32, "address-house-number", false);
        var postal = text(text.postalCode(), 32, "address-postal-code", false);
        if (patch.isPrimary().present() && patch.isPrimary().value() == null) {
            throw new InvalidText("address-is-primary-must-be-boolean");
        }
        var coordinates = patch.coordinates().canonical();
        return new AddressInformationPatch(type,
                new AddressInformationPatch.TextChanges(country, line1, division, line2, house, postal),
                patch.isPrimary(), coordinates);
    }

    /** POST uses the same field precedence, but materializes defaults and requires its three fields. */
    public static AddressInformation create(AddressInformationPatch input) {
        require(input.addressType(), "address-type-required");
        var patch = canonicalCreation(input);
        return apply(null, patch);
    }

    private static AddressInformationPatch canonicalCreation(AddressInformationPatch input) {
        // Missing required fields become present nulls so canonical validation keeps its fixed order.
        var text = input.text();
        return canonical(new AddressInformationPatch(input.addressType(),
                new AddressInformationPatch.TextChanges(FieldChange.present(text.countryCode().value()),
                        FieldChange.present(text.addressLine1().value()), text.administrativeDivisionCode(),
                        text.addressLine2(), text.houseNumber(), text.postalCode()),
                input.isPrimary(), input.coordinates()));
    }

    public static Decision decide(AddressInformation previous, AddressInformationPatch patch) {
        Objects.requireNonNull(previous, "previous");
        var effective = apply(previous, canonical(patch));
        return new Decision(effective, !effective.equals(previous));
    }

    private static AddressInformation apply(AddressInformation previous, AddressInformationPatch patch) {
        var before = previous == null ? null : previous.text();
        var text = patch.text();
        var effective = new AddressText(text.countryCode().apply(before == null ? null : before.countryCode()),
                text.addressLine1().apply(before == null ? null : before.addressLine1()),
                text.administrativeDivisionCode().apply(before == null ? null : before.administrativeDivisionCode()),
                text.addressLine2().apply(before == null ? null : before.addressLine2()),
                text.houseNumber().apply(before == null ? null : before.houseNumber()),
                text.postalCode().apply(before == null ? null : before.postalCode()));
        return new AddressInformation(patch.addressType().present() ? AddressType.valueOf(patch.addressType().value())
                : Objects.requireNonNull(previous).addressType(), effective,
                patch.coordinates().apply(previous == null ? AddressCoordinates.empty() : previous.coordinates()),
                patch.isPrimary().present() ? Boolean.TRUE.equals(patch.isPrimary().value())
                        : previous != null && previous.isPrimary());
    }

    private static FieldChange text(FieldChange field, int limit, String name, boolean required) {
        return field.present() ? FieldChange.present(AddressText.text(field.value(), limit, name, required)) : field;
    }

    private static void require(FieldChange field, String rule) {
        if (!field.present()) throw new InvalidText(rule);
    }
}
