package com.alexastudillo.companyregistry.application.context;

import java.util.Objects;
import java.util.UUID;

/** Validated caller identity and opaque correlation, independent of HTTP and MDC. */
public record OperationContext(UUID tenantId, String actor, String processId) {
    public OperationContext {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(processId, "processId");
        if (actor.isBlank() || actor.codePointCount(0, actor.length()) > 128
                || actor.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("actor-invalid");
        }
        if (!UUID.fromString(processId).toString().equalsIgnoreCase(processId)) {
            throw new IllegalArgumentException("process-id-invalid");
        }
    }
}
