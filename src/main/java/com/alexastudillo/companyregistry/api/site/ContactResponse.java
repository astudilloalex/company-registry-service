package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.companyregistry.application.site.ContactMutation;
import com.alexastudillo.companyregistry.domain.model.ContactType;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContactResponse(UUID contactId, UUID siteId, long siteVersion, ContactType contactType, String value,
        boolean isPrimary, Instant createdAt, String createdBy, Instant updatedAt, String updatedBy, String label) {
    public static ContactResponse from(ContactMutation.View view) {
        var row = view.contact();
        return new ContactResponse(row.id(), row.siteId(), view.siteVersion(), row.type(), row.value(), row.primary(),
                row.audit().createdAt(), row.audit().createdBy(), row.audit().updatedAt(), row.audit().updatedBy(), row.label());
    }
}
