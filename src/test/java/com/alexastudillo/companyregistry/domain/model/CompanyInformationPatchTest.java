package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CompanyInformationPatchTest {
    private final CompanyInformation previous = new CompanyInformation("CODE", "NAME", "DESCRIPTION", "https://host/Logo");

    @Test
    void preservesOmittedFieldsAndClearsOnlyOptionalFields() {
        assertEquals(previous, CompanyInformationPatch.empty().apply(previous));
        var patch = new CompanyInformationPatch(FieldChange.absent(), FieldChange.present("NEW"),
                FieldChange.present(null), FieldChange.present(null));
        assertEquals(new CompanyInformation("CODE", "NEW", null, null), patch.apply(previous));
        assertNotEquals(FieldChange.absent(), FieldChange.present(null));
        assertThrows(IllegalArgumentException.class, () -> new FieldChange(false, "ignored"));
    }

    @Test
    void rejectsClearingEachRequiredField() {
        var clearCode = new CompanyInformationPatch(FieldChange.present(null),
                FieldChange.absent(), FieldChange.absent(), FieldChange.absent());
        assertThrows(IllegalArgumentException.class, () -> clearCode.apply(previous));
        var clearOperatingName = new CompanyInformationPatch(FieldChange.absent(),
                FieldChange.present(null), FieldChange.absent(), FieldChange.absent());
        assertThrows(IllegalArgumentException.class, () -> clearOperatingName.apply(previous));
    }
}
