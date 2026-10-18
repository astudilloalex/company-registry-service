package com.alexastudillo.companyregistry.application.company;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.domain.model.CompanyStatus;
import com.alexastudillo.companyregistry.domain.text.OpaqueValue;
import java.util.Objects;
import java.util.UUID;

/** Empty intent for the three independent Company lifecycle operations; results use CompanyRecord. */
public final class CompanyLifecycle {
    private CompanyLifecycle() {}

    public enum Operation {
        ACTIVATE("company.activate.v1", CompanyStatus.ACTIVE),
        DEACTIVATE("company.deactivate.v1", CompanyStatus.INACTIVE),
        ARCHIVE("company.archive.v1", CompanyStatus.ARCHIVED);

        private final String namespace;
        private final CompanyStatus target;

        Operation(String namespace, CompanyStatus target) {
            this.namespace = namespace;
            this.target = target;
        }

        public String namespace() { return namespace; }
        public CompanyStatus target() { return target; }
    }

    public record Command(OperationContext context, UUID companyId, long expectedVersion,
            String idempotencyKey, Operation operation) {
        public Command {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(companyId, "companyId");
            Objects.requireNonNull(operation, "operation");
            if (expectedVersion < 0) throw new IllegalArgumentException("expected-version-invalid");
            if (idempotencyKey != null) OpaqueValue.required(idempotencyKey, 128);
        }
    }
}
