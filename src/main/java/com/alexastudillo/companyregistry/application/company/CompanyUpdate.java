package com.alexastudillo.companyregistry.application.company;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.domain.model.CompanyInformationPatch;
import com.alexastudillo.companyregistry.domain.text.OpaqueValue;
import java.util.Objects;
import java.util.UUID;

public final class CompanyUpdate {
    public static final String OPERATION = "company.update.v1";
    private CompanyUpdate() {}

    public record Command(OperationContext context, UUID companyId, long expectedVersion,
            String idempotencyKey, CompanyInformationPatch patch) {
        public Command {
            Objects.requireNonNull(context);
            Objects.requireNonNull(companyId);
            Objects.requireNonNull(patch);
            if (expectedVersion < 0) throw new IllegalArgumentException("expected-version-invalid");
            if (idempotencyKey != null) OpaqueValue.required(idempotencyKey, 128);
        }
    }
}
