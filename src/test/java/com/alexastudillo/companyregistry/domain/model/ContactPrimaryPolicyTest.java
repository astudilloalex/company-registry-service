package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContactPrimaryPolicyTest {
    @Test
    void eachTypeAllowsZeroAndOnlyDemotesTheDestinationWhenSelecting() {
        var target = UUID.randomUUID();
        var prior = UUID.randomUUID();
        for (var type : ContactType.values()) {
            var primary = new ContactInformation(type, "unused-by-selection", null, true);
            var destination = new ContactPrimaryPolicy.Selection(prior, type);
            assertNull(ContactPrimaryPolicy.decide(target, primary, null).demoteContactId());
            assertEquals(prior, ContactPrimaryPolicy.decide(target, primary, destination).demoteContactId());
            assertNull(ContactPrimaryPolicy.decide(prior, primary, destination).demoteContactId());
            var unselected = new ContactInformation(type, primary.value(), null, false);
            assertNull(ContactPrimaryPolicy.decide(target, unselected, destination).demoteContactId());
        }
    }

    @Test
    void rejectsSelectionFromAnotherCategory() {
        var target = UUID.randomUUID();
        var information = new ContactInformation(ContactType.EMAIL, "a@example.com", null, true);
        var wrongType = new ContactPrimaryPolicy.Selection(UUID.randomUUID(), ContactType.PHONE);
        assertThrows(IllegalArgumentException.class, () -> ContactPrimaryPolicy.decide(target, information, wrongType));
    }
}
