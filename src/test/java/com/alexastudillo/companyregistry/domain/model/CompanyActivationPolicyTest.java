package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CompanyActivationPolicyTest {
    @Test
    void requiresActiveLegalEntityAndChecksKindBeforeState() {
        var absent = assertThrows(CompanyLifecycleRejected.class, () -> CompanyActivationPolicy.requireParty(null));
        assertEquals(CompanyLifecycleRejected.Reason.PARTY_NOT_FOUND, absent.reason());
        for (var kind : new String[] {"LEGAL_ENTITY", "NATURAL_PERSON"}) {
            for (var state : new String[] {"DRAFT", "ACTIVE", "INACTIVE", "ARCHIVED"}) {
                var facts = new CompanyActivationPolicy.PartyFacts("LEGAL_ENTITY".equals(kind), "ACTIVE".equals(state));
                if (facts.legalEntity() && facts.active()) {
                    assertDoesNotThrow(() -> CompanyActivationPolicy.requireParty(facts));
                } else {
                    var failure = assertThrows(CompanyLifecycleRejected.class,
                            () -> CompanyActivationPolicy.requireParty(facts));
                    assertEquals(facts.legalEntity() ? CompanyLifecycleRejected.Reason.PARTY_MUST_BE_ACTIVE
                            : CompanyLifecycleRejected.Reason.PARTY_MUST_BE_LEGAL_ENTITY, failure.reason());
                }
                assertEquals("LEGAL_ENTITY".equals(kind) && !"ARCHIVED".equals(state),
                        RegistrationPolicy.eligible(kind, state));
            }
        }
    }

    @Test
    void ignoresClosedHistoryAndRequiresCurrentActiveHeadquartersWithoutChildFacts() {
        var absent = assertThrows(CompanyLifecycleRejected.class, () -> CompanyActivationPolicy.requireHeadquarters(null));
        assertEquals(CompanyLifecycleRejected.Reason.LEGAL_HEADQUARTERS_REQUIRED, absent.reason());
        for (var status : SiteStatus.values()) {
            if (status == SiteStatus.ACTIVE) {
                assertDoesNotThrow(() -> CompanyActivationPolicy.requireHeadquarters(status));
            } else {
                var failure = assertThrows(CompanyLifecycleRejected.class,
                        () -> CompanyActivationPolicy.requireHeadquarters(status));
                assertEquals(status == SiteStatus.CLOSED ? CompanyLifecycleRejected.Reason.LEGAL_HEADQUARTERS_REQUIRED
                        : CompanyLifecycleRejected.Reason.ACTIVE_HEADQUARTERS_REQUIRED, failure.reason());
            }
        }
    }
}
