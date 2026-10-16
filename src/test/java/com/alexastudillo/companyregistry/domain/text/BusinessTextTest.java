package com.alexastudillo.companyregistry.domain.text;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class BusinessTextTest {
    @Test
    void preservesAccentsAndInternalSpacesAndIsIdempotent() {
        var result = BusinessText.required("  compañía  ágil  ", 200);
        assertEquals("COMPAÑÍA  ÁGIL", result);
        assertEquals(result, BusinessText.required(result, 200));
        assertEquals("SEDE NORTE", BusinessText.required("  sede norte ", 200));
    }

    @Test
    void doesNotDependOnTheDefaultLocale() {
        var previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("IZMIR", BusinessText.required("izmir", 200));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void validatesCanonicalLengthAfterExpansion() {
        assertEquals("STRASSE", BusinessText.required("straße", 7));
        assertEquals("text-too-long", assertThrows(InvalidText.class,
                () -> BusinessText.required("straße", 6)).rule());
    }

    @Test
    void distinguishesOmittedOptionalTextFromBlankProvidedText() {
        assertNull(BusinessText.optional(null, 500));
        assertEquals("text-required", assertThrows(InvalidText.class,
                () -> BusinessText.required(null, 200)).rule());
        for (var input : new String[] {"", " ", "\t\n", "\u2003"}) {
            assertEquals("text-blank", assertThrows(InvalidText.class,
                    () -> BusinessText.optional(input, 500)).rule());
        }
    }
}
