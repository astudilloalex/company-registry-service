package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.RegistryData;
import com.alexastudillo.companyregistry.domain.model.AddressInformationPatch;
import com.alexastudillo.companyregistry.domain.text.OpaqueValue;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Original HU06 intentions and neutral accepted results; no transport or storage codecs. */
public final class AddressMutation {
    private static final String SCOPE = "scope";

    private AddressMutation() {}

    public enum Operation {
        CREATE("company.site.address.create.v1", (short) 201),
        UPDATE("company.site.address.update.v1", (short) 200),
        DELETE("company.site.address.delete.v1", (short) 200);

        private final String namespace;
        private final short resultStatus;

        Operation(String namespace, short resultStatus) {
            this.namespace = namespace;
            this.resultStatus = resultStatus;
        }

        public String namespace() { return namespace; }
        public short resultStatus() { return resultStatus; }
    }

    public record Intent(Operation operation, UUID addressId, AddressInformationPatch patch) {
        public Intent {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(patch, "patch");
            if ((operation == Operation.CREATE) != (addressId == null)) {
                throw new IllegalArgumentException("address-intent-identity-invalid");
            }
            if (operation == Operation.DELETE && !patch.equals(AddressInformationPatch.empty())) {
                throw new IllegalArgumentException("address-delete-has-information");
            }
        }
    }

    public record Command(OperationContext context, AddressScope scope, long expectedVersion,
            String idempotencyKey, Intent intent) {
        public Command {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(scope, SCOPE);
            Objects.requireNonNull(intent, "intent");
            if (!scope.tenantId().equals(context.tenantId())) throw new IllegalArgumentException("address-tenant-mismatch");
            if (expectedVersion < 0) throw new IllegalArgumentException("expected-version-invalid");
            if (intent.operation() == Operation.CREATE || idempotencyKey != null) {
                OpaqueValue.requireValid(idempotencyKey, 128);
            }
        }
    }

    public sealed interface Result permits View, Deletion {
        AddressScope scope();
        UUID addressId();
        long siteVersion();
    }

    public record View(AddressScope scope, RegistryData.AddressRecord address, long siteVersion) implements Result {
        public View {
            Objects.requireNonNull(scope, SCOPE);
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(address.id(), "addressId");
            if (!scope.siteId().equals(address.siteId())) throw new IllegalArgumentException("address-site-mismatch");
            if (siteVersion < 0) throw new IllegalArgumentException("site-version-invalid");
            if (address.deletedAt() != null || address.deletedBy() != null) {
                throw new IllegalArgumentException("address-view-is-deleted");
            }
        }

        @Override public UUID addressId() { return address.id(); }
    }

    public record Deletion(AddressScope scope, UUID addressId, long siteVersion, Instant deletedAt,
            String deletedBy) implements Result {
        public Deletion {
            Objects.requireNonNull(scope, SCOPE);
            Objects.requireNonNull(addressId, "addressId");
            Objects.requireNonNull(deletedAt, "deletedAt");
            OpaqueValue.requireValid(deletedBy, 128);
            if (siteVersion < 1) throw new IllegalArgumentException("site-version-invalid");
        }
    }
}
