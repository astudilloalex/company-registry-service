package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alexastudillo.companyregistry.domain.model.AddressInformationPatch.PrimaryChange;
import com.alexastudillo.companyregistry.domain.model.AddressInformationPatch.TextChanges;
import com.alexastudillo.companyregistry.domain.text.InvalidText;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AddressInformationPolicyTest {
    private static final FieldChange OMIT = FieldChange.absent();
    private static final FieldChange CLEAR = FieldChange.present(null);
    private static final AddressInformation PREVIOUS = new AddressInformation(AddressType.PHYSICAL,
            new AddressText("EC", "AVENIDA  JOSÉ", "EC-P", "PISO 2", "12A", "123"),
            new AddressCoordinates(BigDecimal.ONE, BigDecimal.TEN), true);

    @Test
    void emptyAndCanonicallyEquivalentPatchesAreNoOpsWithoutMutatingIntent() {
        var empty = AddressInformationPolicy.decide(PREVIOUS, AddressInformationPatch.empty());
        assertEquals(PREVIOUS, empty.information());
        assertFalse(empty.changed());
        var original = new AddressInformationPatch(FieldChange.present(" physical "),
                new TextChanges(FieldChange.present(" ec "), FieldChange.present(" avenida  josé "),
                        OMIT, OMIT, OMIT, OMIT), PrimaryChange.absent(),
                new AddressCoordinates.Change(true, new BigDecimal("1.00000000"), true, new BigDecimal("10.00")));
        var equivalent = AddressInformationPolicy.decide(PREVIOUS, original);
        assertEquals(PREVIOUS, equivalent.information());
        assertFalse(equivalent.changed());
        assertEquals(" physical ", original.addressType().value());
        assertEquals(" ec ", original.text().countryCode().value());
    }

    @Test
    void explicitClearsAffectOnlyOptionalFieldsAndOmissionKeepsCountryDivisionAndPrimary() {
        var patch = new AddressInformationPatch(OMIT,
                new TextChanges(OMIT, OMIT, CLEAR, CLEAR, FieldChange.present(" 34b "), CLEAR),
                PrimaryChange.absent(), new AddressCoordinates.Change(true, null, true, null));
        var decision = AddressInformationPolicy.decide(PREVIOUS, patch);
        assertTrue(decision.changed());
        assertEquals(new AddressInformation(AddressType.PHYSICAL,
                new AddressText("EC", "AVENIDA  JOSÉ", null, null, "34B", null), AddressCoordinates.empty(), true),
                decision.information());
        assertEquals("EC-P", PREVIOUS.text().administrativeDivisionCode());
        var changedCountry = new AddressInformationPatch(OMIT,
                new TextChanges(FieldChange.present("CO"), OMIT, OMIT, OMIT, OMIT, OMIT),
                PrimaryChange.absent(), AddressCoordinates.Change.absent());
        assertEquals("EC-P", AddressInformationPolicy.decide(PREVIOUS, changedCountry).information()
                .text().administrativeDivisionCode());
    }

    @Test
    void clearingAnAlreadyNullOptionalIsNoOpButRemainsPresentInCanonicalIntent() {
        var previous = new AddressInformation(AddressType.MAILING,
                new AddressText("EC", "A", null, null, null, null), AddressCoordinates.empty(), false);
        var patch = new AddressInformationPatch(OMIT, new TextChanges(OMIT, OMIT, CLEAR, CLEAR, CLEAR, CLEAR),
                PrimaryChange.absent(), new AddressCoordinates.Change(true, null, true, null));
        assertFalse(AddressInformationPolicy.decide(previous, patch).changed());
        assertTrue(AddressInformationPolicy.canonical(patch).text().postalCode().present());
        assertFalse(AddressInformationPolicy.canonical(AddressInformationPatch.empty()).text().postalCode().present());
    }

    @Test
    void rejectsNullRequiredFieldsInFixedOrder() {
        var text = new TextChanges(CLEAR, CLEAR, OMIT, OMIT, OMIT, OMIT);
        assertRule("address-type-required", new AddressInformationPatch(CLEAR, text,
                PrimaryChange.absent(), AddressCoordinates.Change.absent()));
        assertRule("address-country-code-required", new AddressInformationPatch(OMIT, text,
                PrimaryChange.absent(), AddressCoordinates.Change.absent()));
        assertRule("address-line1-required", new AddressInformationPatch(OMIT,
                new TextChanges(OMIT, CLEAR, OMIT, OMIT, OMIT, OMIT),
                PrimaryChange.absent(), AddressCoordinates.Change.absent()));
        assertRule("address-is-primary-must-be-boolean", new AddressInformationPatch(OMIT, TextChanges.empty(),
                new PrimaryChange(true, null), AddressCoordinates.Change.absent()));
    }

    @Test
    void creationRequiresExplicitContentAndMaterializesDefaults() {
        var input = new AddressInformationPatch(FieldChange.present("mailing"),
                new TextChanges(FieldChange.present("ec"), FieldChange.present("a"), OMIT, OMIT, OMIT, OMIT),
                PrimaryChange.absent(), AddressCoordinates.Change.absent());
        assertEquals(new AddressInformation(AddressType.MAILING, new AddressText("EC", "A", null, null, null, null),
                AddressCoordinates.empty(), false), AddressInformationPolicy.create(input));
        var empty = AddressInformationPatch.empty();
        var missingType = assertThrows(InvalidText.class, () -> AddressInformationPolicy.create(empty));
        assertEquals("address-type-required", missingType.rule());
        var missingCountry = new AddressInformationPatch(input.addressType(), TextChanges.empty(),
                PrimaryChange.absent(), AddressCoordinates.Change.absent());
        var failure = assertThrows(InvalidText.class, () -> AddressInformationPolicy.create(missingCountry));
        assertEquals("address-country-code-required", failure.rule());
    }

    private static void assertRule(String rule, AddressInformationPatch patch) {
        var failure = assertThrows(InvalidText.class, () -> AddressInformationPolicy.decide(PREVIOUS, patch));
        assertEquals(rule, failure.rule());
    }
}
