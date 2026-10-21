package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.companyregistry.application.site.ContactMutation;
import java.time.Instant;
import java.util.UUID;

public record ContactDeletionResponse(UUID contactId, UUID siteId, long siteVersion, Instant deletedAt, String deletedBy) {
    public static ContactDeletionResponse from(ContactMutation.Deletion result) {
        return new ContactDeletionResponse(result.contactId(), result.scope().siteId(), result.siteVersion(), result.deletedAt(), result.deletedBy());
    }
}
