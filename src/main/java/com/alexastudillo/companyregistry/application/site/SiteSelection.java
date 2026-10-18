package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.domain.model.SiteRegistrationPolicy;
import com.alexastudillo.companyregistry.domain.model.SiteStatus;
import com.alexastudillo.companyregistry.domain.model.SiteType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class SiteSelection {
    private SiteSelection() {}

    public record Filters(String code, SiteType siteType, SiteStatus status) {
        public Filters {
            code = code == null ? null : SiteRegistrationPolicy.code(code);
        }
    }

    public record Boundary(Instant createdAt, UUID id) {
        public Boundary {
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(id, "id");
        }
    }

    public record Request(Filters filters, int limit, String cursor) {
        public Request {
            Objects.requireNonNull(filters, "filters");
            if (limit < 1 || limit > 200) throw new IllegalArgumentException("site-page-limit-invalid");
        }
    }
}
