package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.domain.text.InvalidText;
import org.junit.jupiter.api.Test;

class SiteRegistrationPolicyTest {
    @Test
    void acceptsAllExactTypesAndDefaultsOnlyWhenAbsent() {
        for (var type : SiteType.values()) {
            assertEquals(type, SiteRegistrationPolicy.canonical("a", "b", FieldChange.present(type.name())).siteType());
        }
        assertEquals(SiteType.BRANCH, SiteRegistrationPolicy.canonical("a", "b", FieldChange.absent()).siteType());
        assertRule("site-type-null-not-allowed", "a", "b", FieldChange.present(null));
        for (var type : new String[] { "", "branch", " BRANCH ", "UNKNOWN" }) {
            assertRule("site-type-not-supported", "a", "b", FieldChange.present(type));
        }
    }

    @Test
    void canonicalizesBeforeCountingUnicodeAndKeepsAccentsAndInnerSpaces() {
        var input = SiteRegistrationPolicy.canonical(" ß á ", " compañía  ágil ", FieldChange.absent());
        assertEquals("SS Á", input.code());
        assertEquals("COMPAÑÍA  ÁGIL", input.name());
        assertEquals("SS".repeat(32), SiteRegistrationPolicy.canonical("ß".repeat(32), "x", FieldChange.absent()).code());
        assertRule("site-code-too-long", "ß".repeat(33), "x", FieldChange.absent());
        assertEquals("SS".repeat(100), SiteRegistrationPolicy.canonical("x", "ß".repeat(100), FieldChange.absent()).name());
        assertRule("site-name-too-long", "x", "ß".repeat(101), FieldChange.absent());
        assertEquals("😀".repeat(64), SiteRegistrationPolicy.code("😀".repeat(64)));
        assertRule("site-code-too-long", "😀".repeat(65), "x", FieldChange.absent());
        assertEquals("😀".repeat(200), SiteRegistrationPolicy.canonical("x", "😀".repeat(200), FieldChange.absent()).name());
        assertRule("site-name-too-long", "x", "😀".repeat(201), FieldChange.absent());
    }

    @Test
    void rejectsMissingOrBlankTextInFieldOrder() {
        for (var value : new String[] { null, "", " ", "\t", "\u2003" }) {
            assertRule("site-code-required", value, null, FieldChange.present(null));
            assertRule("site-name-required", "x", value, FieldChange.present(null));
        }
    }

    @Test
    void admitsExactlyTheThreeNonArchivedCompanyStates() {
        for (var state : CompanyStatus.values()) {
            assertEquals(state != CompanyStatus.ARCHIVED, SiteRegistrationPolicy.admits(state));
        }
        assertFalse(SiteRegistrationPolicy.admits(null));
    }

    private static void assertRule(String rule, String code, String name, FieldChange type) {
        assertEquals(rule, assertThrows(InvalidText.class,
                () -> SiteRegistrationPolicy.canonical(code, name, type)).rule());
    }
}
