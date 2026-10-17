package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.application.company.CompanyRegistration;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegistrationPolicyTest {
    private static final UUID PARTY = UUID.randomUUID();

    @Test
    void onlyLegalEntitiesInTheThreeApprovedStatesAreEligible() {
        for (var state : new String[] { "DRAFT", "ACTIVE", "INACTIVE" }) {
            assertTrue(RegistrationPolicy.eligible("LEGAL_ENTITY", state));
            assertFalse(RegistrationPolicy.eligible("NATURAL_PERSON", state));
        }
        assertFalse(RegistrationPolicy.eligible("LEGAL_ENTITY", "ARCHIVED"));
        assertFalse(RegistrationPolicy.eligible("LEGAL_ENTITY", null));
    }

    @Test
    void canonicalizesBeforeCountingCodePointsAndPreservesOpaqueUrl() {
        var input = RegistrationPolicy.canonical(PARTY, " ß ", " compañía  ágil ", " descripción ",
                "https://example.com/Logo.png?Token=aB");
        assertEquals("SS", input.code());
        assertEquals("COMPAÑÍA  ÁGIL", input.operatingName());
        assertEquals("DESCRIPCIÓN", input.description());
        assertEquals("https://example.com/Logo.png?Token=aB", input.logoUrl());
        assertEquals(64, RegistrationPolicy.canonical(PARTY, "ß".repeat(32), "a", null, null).code().length());
        var longCode = "ß".repeat(33);
        assertThrows(IllegalArgumentException.class,
                () -> RegistrationPolicy.canonical(PARTY, longCode, "a", null, null));
        var longOperatingName = "ß".repeat(101);
        assertThrows(IllegalArgumentException.class,
                () -> RegistrationPolicy.canonical(PARTY, "a", longOperatingName, null, null));
        var longDescription = "ß".repeat(251);
        assertThrows(IllegalArgumentException.class,
                () -> RegistrationPolicy.canonical(PARTY, "a", "a", longDescription, null));
    }

    @Test
    void distinguishesAbsentOptionalsFromBlankAndRejectsInvalidUrls() {
        var input = RegistrationPolicy.canonical(PARTY, "a", "a", null, null);
        assertNull(input.description());
        assertNull(input.logoUrl());
        for (var text : new String[] { "", " ", "\t" }) {
            assertThrows(IllegalArgumentException.class,
                    () -> RegistrationPolicy.canonical(PARTY, text, "a", null, null));
            assertThrows(IllegalArgumentException.class,
                    () -> RegistrationPolicy.canonical(PARTY, "a", text, null, null));
            assertThrows(IllegalArgumentException.class,
                    () -> RegistrationPolicy.canonical(PARTY, "a", "a", text, null));
        }
        for (var url : new String[] { "", " https://example.com", "https://example.com ", "/Logo.png",
                "ftp://example.com/a", "https://", "https://example.com/" + "a".repeat(1000) }) {
            assertThrows(IllegalArgumentException.class,
                    () -> RegistrationPolicy.canonical(PARTY, "a", "a", null, url));
        }
    }

    @Test
    void capturesOneInstantAndTheExactActorForInitialDraft() {
        var context = new OperationContext(UUID.randomUUID(), " Actor-aB ", UUID.randomUUID().toString());
        var command = new CompanyRegistration.Command(context, " Key-aB ", PARTY, " acme ", " Ágil ", null, null);
        var id = UUID.fromString("0199e000-0000-7000-8000-000000000001");
        var instant = Instant.parse("2026-10-17T14:30:00.123456789Z");
        var companyRegistration = CompanyRegistration.candidate(command, Clock.fixed(instant, ZoneOffset.UTC),
                () -> id);
        assertEquals(id, companyRegistration.company().id());
        assertEquals(context.tenantId(), companyRegistration.company().tenantId());
        assertEquals(PARTY, companyRegistration.company().partyId());
        assertEquals(CompanyStatus.DRAFT, companyRegistration.company().status());
        assertEquals(0, companyRegistration.company().version().value());
        assertEquals(Instant.parse("2026-10-17T14:30:00.123456Z"), companyRegistration.audit().createdAt());
        assertEquals(companyRegistration.audit().createdAt(), companyRegistration.audit().updatedAt());
        assertEquals(" Actor-aB ", companyRegistration.audit().createdBy());
        assertEquals(companyRegistration.audit().createdBy(), companyRegistration.audit().updatedBy());
        assertEquals(" Key-aB ", command.idempotencyKey());
        assertEquals(" acme ", command.code());
    }
}
