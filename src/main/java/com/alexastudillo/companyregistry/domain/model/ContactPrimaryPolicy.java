package com.alexastudillo.companyregistry.domain.model;

import java.util.Objects;
import java.util.UUID;

/** A primary is selected explicitly; the previous type never receives an automatic replacement. */
public final class ContactPrimaryPolicy {
    private ContactPrimaryPolicy() {}

    public record Selection(UUID contactId, ContactType contactType) {
        public Selection {
            Objects.requireNonNull(contactId, "contactId");
            Objects.requireNonNull(contactType, "contactType");
        }
    }

    public record Decision(UUID demoteContactId) {}

    /** Destination primary is read under the Site protection; demote it before promoting the target. */
    public static Decision decide(UUID targetId, ContactInformation effective, Selection destinationPrimary) {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(effective, "effective");
        if (destinationPrimary != null && destinationPrimary.contactType() != effective.contactType()) {
            throw new IllegalArgumentException("primary-selection-type-mismatch");
        }
        UUID demote = effective.isPrimary() && destinationPrimary != null
                && !targetId.equals(destinationPrimary.contactId()) ? destinationPrimary.contactId() : null;
        return new Decision(demote);
    }
}
