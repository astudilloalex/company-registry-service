package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.domain.model.ContactType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class ContactSelection {
    public static final int DEFAULT_LIMIT = 50;
    private ContactSelection() {}

    public record Filters(ContactType contactType, Boolean isPrimary) {}

    public record Boundary(Instant createdAt, UUID contactId) {
        public Boundary {
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(contactId, "contactId");
        }
    }

    public record Request(Filters filters, int limit, String cursor) {
        public Request {
            Objects.requireNonNull(filters, "filters");
            if (limit < 1 || limit > 200) throw new IllegalArgumentException("contact-page-limit-invalid");
            if (cursor != null && (cursor.isBlank() || cursor.length() > 4096)) {
                throw new IllegalArgumentException("contact-cursor-invalid-or-incompatible");
            }
        }
    }
}
