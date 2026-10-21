package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.domain.text.InvalidText;
import org.junit.jupiter.api.Test;

class ContactIntentPolicyV1Test {
    private static ContactInformationPatch valueOnly(String value) {
        return new ContactInformationPatch(FieldChange.absent(), FieldChange.present(value), FieldChange.absent(),
                ContactInformationPatch.PrimaryChange.absent());
    }

    @Test
    void canonicalizesValueWithoutReadingOrInferringCurrentType() {
        var email = ContactIntentPolicyV1.canonical(valueOnly(" A@Example.COM "));
        assertEquals(ContactIntentPolicyV1.Family.EMAIL, email.valueFamily());
        assertEquals("a@example.com", email.patch().value().value());
        assertFalse(email.patch().contactType().present());
        var telephone = ContactIntentPolicyV1.canonical(valueOnly("+1 (202) 555-0123"));
        assertEquals(ContactIntentPolicyV1.Family.PHONE, telephone.valueFamily());
        assertEquals(telephone, ContactIntentPolicyV1.canonical(valueOnly("+12025550123")));
        // Classification belongs to effective validation, not the lexical intention.
        assertEquals("+442079460018", ContactIntentPolicyV1.canonical(valueOnly("+44 20 7946 0018")).patch().value().value());
    }

    @Test
    void preservesPresenceIndependentlyOfCanonicalValue() {
        var omitted = ContactIntentPolicyV1.canonical(valueOnly("a@example.com"));
        var explicit = new ContactInformationPatch(FieldChange.present("email"), FieldChange.present("A@Example.com"),
                FieldChange.absent(), ContactInformationPatch.PrimaryChange.absent());
        assertNotEquals(omitted, ContactIntentPolicyV1.canonical(explicit));
        var empty = ContactIntentPolicyV1.canonical(ContactInformationPatch.empty());
        assertNull(empty.valueFamily());
        var clear = new ContactInformationPatch(FieldChange.absent(), FieldChange.absent(), FieldChange.present(null),
                ContactInformationPatch.PrimaryChange.absent());
        assertNotEquals(empty, ContactIntentPolicyV1.canonical(clear));
    }

    @Test
    void invalidFamilyIsRejectedWithoutStoredContact() {
        for (String value : new String[] {null, "", "national", "2025550123"}) {
            var input = valueOnly(value);
            assertThrows(InvalidText.class, () -> ContactIntentPolicyV1.canonical(input));
        }
    }
}
