package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.domain.model.AddressType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class AddressSelection {
    public static final int DEFAULT_LIMIT = 50;
    private AddressSelection() {}

    public record Filters(AddressType addressType, Boolean isPrimary) {}

    public record Boundary(Instant createdAt, UUID addressId) {
        public Boundary {
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(addressId, "addressId");
        }
    }

    public record Request(Filters filters, int limit, String cursor) {
        public Request {
            Objects.requireNonNull(filters, "filters");
            if (limit < 1 || limit > 200) throw new IllegalArgumentException("address-page-limit-invalid");
            if (cursor != null && (cursor.isBlank() || cursor.length() > 4096)) {
                throw new IllegalArgumentException("address-cursor-invalid-or-incompatible");
            }
        }
    }
}
