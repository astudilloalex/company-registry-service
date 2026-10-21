package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.domain.text.InvalidText;
import org.junit.jupiter.api.Test;

class ContactInformationPolicyTest {
    private static ContactInformationPatch patch(String type, String value, String label, Boolean primary) {
        return new ContactInformationPatch(FieldChange.present(type), FieldChange.present(value),
                FieldChange.present(label), new ContactInformationPatch.PrimaryChange(true, primary));
    }

    @Test
    void creationMaterializesDefaultsAndCanonicalText() {
        var input = new ContactInformationPatch(FieldChange.present(" email "), FieldChange.present(" Sales@Example.COM "),
                FieldChange.absent(), ContactInformationPatch.PrimaryChange.absent());
        var result = ContactInformationPolicy.create(input);
        assertEquals(new ContactInformation(ContactType.EMAIL, "sales@example.com", null, false), result);
        var labelled = ContactInformationPolicy.create(patch("EMAIL", "a@example.com", " atención  comercial ", true));
        assertEquals("ATENCIÓN  COMERCIAL", labelled.label());
        assertTrue(labelled.isPrimary());
    }

    @Test
    void validatesRequiredValuesAndPrimaryBeforeEffectiveCombination() {
        var missing = ContactInformationPatch.empty();
        assertEquals("contact-type-required", assertThrows(InvalidText.class,
                () -> ContactInformationPolicy.create(missing)).rule());
        for (String type : new String[] {null, "", " ", "FAX"}) {
            var input = patch(type, "a@example.com", null, false);
            assertThrows(InvalidText.class, () -> ContactInformationPolicy.create(input));
        }
        for (String value : new String[] {null, "", " "}) {
            var input = patch("EMAIL", value, null, false);
            assertEquals("contact-value-required", assertThrows(InvalidText.class,
                    () -> ContactInformationPolicy.create(input)).rule());
        }
        var nullPrimary = patch("EMAIL", "a@example.com", null, null);
        assertEquals("contact-is-primary-must-be-boolean", assertThrows(InvalidText.class,
                () -> ContactInformationPolicy.create(nullPrimary)).rule());
    }

    @Test
    void labelUsesCodePointsAfterUppercaseExpansion() {
        assertEquals("SS".repeat(40), ContactInformationPolicy.label("ß".repeat(40)));
        assertEquals("😀".repeat(80), ContactInformationPolicy.label("😀".repeat(80)));
        String tooLong = "ß".repeat(41);
        assertEquals("contact-label-too-long", assertThrows(InvalidText.class,
                () -> ContactInformationPolicy.label(tooLong)).rule());
        assertEquals("contact-label-blank", assertThrows(InvalidText.class,
                () -> ContactInformationPolicy.label(" ")).rule());
        assertNull(ContactInformationPolicy.label(null));
    }

    @Test
    void omissionClearAndCanonicalEqualityAreDistinct() {
        var before = new ContactInformation(ContactType.EMAIL, "a@example.com", "VENTAS", true);
        assertFalse(ContactInformationPolicy.decide(before, ContactInformationPatch.empty()).changed());
        var equivalent = patch(" email ", " A@Example.com ", " ventas ", true);
        assertFalse(ContactInformationPolicy.decide(before, equivalent).changed());
        var clear = new ContactInformationPatch(FieldChange.absent(), FieldChange.absent(), FieldChange.present(null),
                ContactInformationPatch.PrimaryChange.absent());
        var decision = ContactInformationPolicy.decide(before, clear);
        assertTrue(decision.changed());
        assertNull(decision.information().label());
        assertTrue(decision.information().isPrimary());
        assertFalse(ContactInformationPolicy.decide(decision.information(), clear).changed());
    }

    @Test
    void reclassificationChecksEffectiveValueAndRetainsOmittedPrimary() {
        var before = new ContactInformation(ContactType.MOBILE, "+12025550123", null, true);
        var whatsapp = new ContactInformationPatch(FieldChange.present("whatsapp"), FieldChange.absent(),
                FieldChange.absent(), ContactInformationPatch.PrimaryChange.absent());
        var result = ContactInformationPolicy.decide(before, whatsapp);
        assertEquals(ContactType.WHATSAPP, result.information().contactType());
        assertEquals(before.value(), result.information().value());
        assertTrue(result.information().isPrimary());
        var incompatible = new ContactInformationPatch(FieldChange.present("email"), FieldChange.absent(),
                FieldChange.absent(), ContactInformationPatch.PrimaryChange.absent());
        assertEquals("contact-value-invalid", assertThrows(InvalidText.class,
                () -> ContactInformationPolicy.decide(before, incompatible)).rule());
        var replacement = patch("EMAIL", "new@example.com", null, false);
        assertEquals(ContactType.EMAIL, ContactInformationPolicy.decide(before, replacement).information().contactType());
    }
}
