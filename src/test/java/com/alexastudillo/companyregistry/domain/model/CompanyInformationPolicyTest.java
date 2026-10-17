package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CompanyInformationPolicyTest {
    @Test
    void decidesEffectiveChangesAndNoOpsInEveryEditableState() {
        var previous = new CompanyInformation("CODE", "COMPAÑÍA  ÁGIL", null, null);
        for (var status : new CompanyStatus[] {CompanyStatus.DRAFT, CompanyStatus.ACTIVE, CompanyStatus.INACTIVE}) {
            assertTrue(CompanyInformationPolicy.editable(status));
            for (var noop : new CompanyInformationPatch[] {CompanyInformationPatch.empty(),
                    new CompanyInformationPatch(FieldChange.present(" code "), FieldChange.present(" compañía  ágil "),
                            FieldChange.present(null), FieldChange.present(null))}) {
                var decision = CompanyInformationPolicy.decide(status, previous, noop);
                assertFalse(decision.changed());
                assertEquals(previous, decision.information());
            }
            var decision = CompanyInformationPolicy.decide(status, previous,
                    new CompanyInformationPatch(FieldChange.absent(), FieldChange.present("new"), FieldChange.absent(), FieldChange.absent()));
            assertTrue(decision.changed());
            assertEquals(new CompanyInformation("CODE", "NEW", null, null), decision.information());
        }
    }

    @Test
    void rejectsArchivedEvenForAnEmptyOrRedundantPatch() {
        assertFalse(CompanyInformationPolicy.editable(CompanyStatus.ARCHIVED));
        var previous = new CompanyInformation("CODE", "NAME", null, null);
        var empty = CompanyInformationPatch.empty();
        assertThrows(IllegalStateException.class, () -> CompanyInformationPolicy.decide(CompanyStatus.ARCHIVED,
                previous, empty));
        var redundant = patch("code", "name", null, null);
        assertThrows(IllegalStateException.class, () -> CompanyInformationPolicy.decide(CompanyStatus.ARCHIVED,
                previous, redundant));
    }

    private CompanyInformationPatch patch(String code, String name, String description, String logo) {
        return new CompanyInformationPatch(FieldChange.present(code), FieldChange.present(name),
                FieldChange.present(description), FieldChange.present(logo));
    }

    @Test
    void normalizesPresentTextAndPreservesExactUrl() {
        var canonical = CompanyInformationPolicy.canonical(patch(" ß ", " compañía  ágil ", " descripción ",
                "https://host/LogoAb.png?Token=aB"));
        assertEquals(patch("SS", "COMPAÑÍA  ÁGIL", "DESCRIPCIÓN", "https://host/LogoAb.png?Token=aB"), canonical);
        assertEquals(CompanyInformationPatch.empty(), CompanyInformationPolicy.canonical(CompanyInformationPatch.empty()));
        assertEquals(patch("A", "A", null, null), CompanyInformationPolicy.canonical(patch("a", "a", null, null)));
    }

    @Test
    void countsCodePointsAfterUppercaseExpansion() {
        assertDoesNotThrow(() -> CompanyInformationPolicy.canonical(patch("ß".repeat(32), "ß".repeat(100),
                "ß".repeat(250), null)));
        var longCode = patch("ß".repeat(33), "a", null, null);
        assertThrows(IllegalArgumentException.class, () -> CompanyInformationPolicy.canonical(longCode));
        var longOperatingName = patch("a", "ß".repeat(101), null, null);
        assertThrows(IllegalArgumentException.class, () -> CompanyInformationPolicy.canonical(longOperatingName));
        var longDescription = patch("a", "a", "ß".repeat(251), null);
        assertThrows(IllegalArgumentException.class, () -> CompanyInformationPolicy.canonical(longDescription));
        assertDoesNotThrow(() -> CompanyInformationPolicy.canonical(patch("𐐀".repeat(64), "a", null, null)));
        var longSurrogateCode = patch("𐐀".repeat(65), "a", null, null);
        assertThrows(IllegalArgumentException.class, () -> CompanyInformationPolicy.canonical(longSurrogateCode));
    }

    @Test
    void rejectsNullRequiredFieldsBlanksAndInvalidUrls() {
        for (String value : new String[] {null, "", " ", "\t"}) {
            var patchCode = patch(value, "a", null, null);
            assertThrows(IllegalArgumentException.class, () -> CompanyInformationPolicy.canonical(patchCode));
            var patchName = patch("a", value, null, null);
            assertThrows(IllegalArgumentException.class, () -> CompanyInformationPolicy.canonical(patchName));
            if (value != null) {
                var patchDescription = patch("a", "a", value, null);
                assertThrows(IllegalArgumentException.class,
                        () -> CompanyInformationPolicy.canonical(patchDescription));
            }
        }
        for (var url : new String[] {"", " https://host", "https://host ", "/Logo.png", "ftp://host/a", "https://",
                "https://host/" + "a".repeat(1000)}) {
            var patchUrl = patch("a", "a", null, url);
            assertThrows(IllegalArgumentException.class, () -> CompanyInformationPolicy.canonical(patchUrl));
        }
        var maximumUrl = "https://host/" + "a".repeat(987);
        assertEquals(1000, maximumUrl.length());
        assertDoesNotThrow(() -> CompanyInformationPolicy.canonical(patch("a", "a", null, maximumUrl)));
    }
}
