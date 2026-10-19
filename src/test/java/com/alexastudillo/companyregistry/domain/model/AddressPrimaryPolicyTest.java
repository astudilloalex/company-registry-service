package com.alexastudillo.companyregistry.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alexastudillo.companyregistry.domain.model.AddressInformationPatch.PrimaryChange;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AddressPrimaryPolicyTest {
    private static final UUID TARGET = UUID.fromString("019a2f00-0000-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("019a2f00-0000-7000-8000-000000000002");
    private static final AddressText TEXT = new AddressText("EC", "A", null, null, null, null);

    @Test
    void primaryReclassificationDisplacesDestinationAndKeepsOmittedSelection() {
        var before = information(AddressType.PHYSICAL, true);
        var effective = AddressInformationPolicy.decide(before, patch(PrimaryChange.absent())).information();
        assertTrue(effective.isPrimary());
        assertEquals(AddressType.MAILING, effective.addressType());
        assertEquals(OTHER, AddressPrimaryPolicy.decide(TARGET, effective,
                new AddressPrimaryPolicy.Selection(OTHER, AddressType.MAILING)).demoteAddressId());
    }

    @Test
    void explicitDemotionPreservesDestinationPrimaryAndDoesNotSelectReplacement() {
        var before = information(AddressType.PHYSICAL, true);
        var effective = AddressInformationPolicy.decide(before, patch(new PrimaryChange(true, false))).information();
        assertFalse(effective.isPrimary());
        assertNull(AddressPrimaryPolicy.decide(TARGET, effective,
                new AddressPrimaryPolicy.Selection(OTHER, AddressType.MAILING)).demoteAddressId());
    }

    @Test
    void nonPrimaryReclassificationRemainsUnselected() {
        var effective = AddressInformationPolicy.decide(information(AddressType.PHYSICAL, false),
                patch(PrimaryChange.absent())).information();
        assertFalse(effective.isPrimary());
        assertNull(AddressPrimaryPolicy.decide(TARGET, effective, null).demoteAddressId());
    }

    @Test
    void explicitSelectionDisplacesOnlyAnotherPrimaryOfSameType() {
        var effective = information(AddressType.PHYSICAL, true);
        assertEquals(OTHER, AddressPrimaryPolicy.decide(TARGET, effective,
                new AddressPrimaryPolicy.Selection(OTHER, AddressType.PHYSICAL)).demoteAddressId());
        assertNull(AddressPrimaryPolicy.decide(TARGET, effective,
                new AddressPrimaryPolicy.Selection(TARGET, AddressType.PHYSICAL)).demoteAddressId());
        assertNull(AddressPrimaryPolicy.decide(TARGET, effective, null).demoteAddressId());
    }

    private static AddressInformationPatch patch(PrimaryChange primary) {
        return new AddressInformationPatch(FieldChange.present("mailing"), AddressInformationPatch.TextChanges.empty(),
                primary, AddressCoordinates.Change.absent());
    }

    private static AddressInformation information(AddressType type, boolean primary) {
        return new AddressInformation(type, TEXT, AddressCoordinates.empty(), primary);
    }
}
