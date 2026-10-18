package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alexastudillo.companyregistry.domain.text.InvalidText;
import org.junit.jupiter.api.Test;

class SiteInformationPolicyTest {
    private static final SiteInformation PREVIOUS = new SiteInformation("CODE", "COMPAÑÍA  ÁGIL", SiteType.HEADQUARTERS);

    @Test
    void preservesOmittedFieldsAndDetectsCanonicalNoOpsWithoutRegistrationDefaults() {
        var empty = SiteInformationPolicy.decide(PREVIOUS, SiteInformationPatch.empty());
        assertEquals(PREVIOUS, empty.information());
        assertFalse(empty.changed());
        var equivalent = SiteInformationPolicy.decide(PREVIOUS, patch(" code ", " compañía  ágil ", FieldChange.absent()));
        assertEquals(PREVIOUS, equivalent.information());
        assertFalse(equivalent.changed());
        var changed = SiteInformationPolicy.decide(PREVIOUS,
                new SiteInformationPatch(FieldChange.absent(), FieldChange.present(" nuevo "), FieldChange.absent()));
        assertEquals(new SiteInformation("CODE", "NUEVO", SiteType.HEADQUARTERS), changed.information());
        assertTrue(changed.changed());
        assertEquals(SiteType.BRANCH, SiteRegistrationPolicy.type(FieldChange.absent()));
    }

    @Test
    void acceptsOnlyExactTypesAndRejectsExplicitNullWithoutClearingAnyField() {
        for (var type : SiteType.values()) {
            var decision = SiteInformationPolicy.decide(PREVIOUS, patch("code", "name", FieldChange.present(type.name())));
            assertEquals(type, decision.information().siteType());
        }
        for (var value : new String[] {null, "", " ", "\t", "\u2003"}) {
            assertRule("site-code-required", patch(value, "name", FieldChange.absent()));
            assertRule("site-name-required", patch("code", value, FieldChange.absent()));
        }
        assertRule("site-type-null-not-allowed", patch("code", "name", FieldChange.present(null)));
        for (var value : new String[] {"branch", " BRANCH ", "", "UNKNOWN"}) {
            assertRule("site-type-not-supported", patch("code", "name", FieldChange.present(value)));
        }
    }

    @Test
    void countsCanonicalCodePointsAfterExpansionAndPreservesAccentsAndInternalSpaces() {
        var canonical = SiteInformationPolicy.canonical(patch(" ß á ", " compañía  ágil ", FieldChange.absent()));
        assertEquals("SS Á", canonical.code().value());
        assertEquals("COMPAÑÍA  ÁGIL", canonical.name().value());
        for (var unit : new String[] {"ß", "😀"}) {
            int codeLimit = unit.equals("ß") ? 32 : 64;
            int nameLimit = unit.equals("ß") ? 100 : 200;
            var maximum = SiteInformationPolicy.canonical(patch(unit.repeat(codeLimit), unit.repeat(nameLimit), FieldChange.absent()));
            assertEquals(64, maximum.code().value().codePointCount(0, maximum.code().value().length()));
            assertEquals(200, maximum.name().value().codePointCount(0, maximum.name().value().length()));
            assertRule("site-code-too-long", patch(unit.repeat(codeLimit + 1), "name", FieldChange.absent()));
            assertRule("site-name-too-long", patch("code", unit.repeat(nameLimit + 1), FieldChange.absent()));
        }
    }

    @Test
    void validatesInCodeNameTypeOrderAndKeepsOriginalIntent() {
        var original = patch(" code ", " name ", FieldChange.absent());
        assertEquals("CODE", SiteInformationPolicy.canonical(original).code().value());
        assertEquals(" code ", original.code().value());
        assertRule("site-code-required", patch(null, null, FieldChange.present(null)));
        assertRule("site-name-required", patch("code", null, FieldChange.present(null)));
        assertEquals(SiteInformationPatch.empty(), SiteInformationPolicy.canonical(SiteInformationPatch.empty()));
    }

    private static SiteInformationPatch patch(String code, String name, FieldChange type) {
        return new SiteInformationPatch(FieldChange.present(code), FieldChange.present(name), type);
    }

    private static void assertRule(String rule, SiteInformationPatch patch) {
        var failure = assertThrows(InvalidText.class, () -> SiteInformationPolicy.canonical(patch));
        assertEquals(rule, failure.rule());
    }
}
