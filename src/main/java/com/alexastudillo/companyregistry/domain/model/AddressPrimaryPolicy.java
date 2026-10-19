package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;
import java.util.UUID;

/** A primary is selected explicitly; the previous type never receives an automatic replacement. */
public final class AddressPrimaryPolicy {
    private AddressPrimaryPolicy() {}

    public record Selection(UUID addressId, AddressType addressType) {
        public Selection {
            Objects.requireNonNull(addressId, "addressId");
            Objects.requireNonNull(addressType, "addressType");
        }
    }

    public record Decision(UUID demoteAddressId) {}

    /** Destination primary is read under the Site protection; demote it before promoting the target. */
    public static Decision decide(UUID targetId, AddressInformation effective, Selection destinationPrimary) {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(effective, "effective");
        if (destinationPrimary != null && destinationPrimary.addressType() != effective.addressType()) {
            throw new IllegalArgumentException("primary-selection-type-mismatch");
        }
        UUID demote = effective.isPrimary() && destinationPrimary != null
                && !targetId.equals(destinationPrimary.addressId()) ? destinationPrimary.addressId() : null;
        return new Decision(demote);
    }
}
