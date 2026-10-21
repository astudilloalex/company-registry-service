package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.RegistryData;
import com.alexastudillo.companyregistry.domain.model.ContactInformationPatch;
import com.alexastudillo.companyregistry.domain.text.OpaqueValue;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Original HU07 intentions and neutral accepted results; no transport or storage codecs. */
public final class ContactMutation {
    private static final String SCOPE = "scope";

    private ContactMutation() {}

    public enum Operation {
        CREATE("company.site.contact.create.v1", (short) 201),
        UPDATE("company.site.contact.update.v1", (short) 200),
        DELETE("company.site.contact.delete.v1", (short) 200);

        private final String namespace;
        private final short resultStatus;

        Operation(String namespace, short resultStatus) {
            this.namespace = namespace;
            this.resultStatus = resultStatus;
        }

        public String namespace() { return namespace; }
        public short resultStatus() { return resultStatus; }
    }

    public record Intent(Operation operation, UUID contactId, ContactInformationPatch patch) {
        public Intent {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(patch, "patch");
            if ((operation == Operation.CREATE) != (contactId == null)) {
                throw new IllegalArgumentException("contact-intent-identity-invalid");
            }
            if (operation == Operation.DELETE && !patch.equals(ContactInformationPatch.empty())) {
                throw new IllegalArgumentException("contact-delete-has-information");
            }
        }
    }

    public record Command(OperationContext context, ContactScope scope, long expectedVersion,
            String idempotencyKey, Intent intent) {
        public Command {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(scope, SCOPE);
            Objects.requireNonNull(intent, "intent");
            if (!scope.tenantId().equals(context.tenantId())) throw new IllegalArgumentException("contact-tenant-mismatch");
            if (expectedVersion < 0) throw new IllegalArgumentException("expected-version-invalid");
            if (intent.operation() == Operation.CREATE || idempotencyKey != null) {
                OpaqueValue.requireValid(idempotencyKey, 128);
            }
        }
    }

    public sealed interface Result permits View, Deletion {
        ContactScope scope();
        UUID contactId();
        long siteVersion();
    }

    public record View(ContactScope scope, RegistryData.ContactRecord contact, long siteVersion) implements Result {
        public View {
            Objects.requireNonNull(scope, SCOPE);
            Objects.requireNonNull(contact, "contact");
            Objects.requireNonNull(contact.id(), "contactId");
            if (!scope.siteId().equals(contact.siteId())) throw new IllegalArgumentException("contact-site-mismatch");
            if (siteVersion < 0) throw new IllegalArgumentException("site-version-invalid");
            if (contact.deletedAt() != null || contact.deletedBy() != null) {
                throw new IllegalArgumentException("contact-view-is-deleted");
            }
        }

        @Override public UUID contactId() { return contact.id(); }
    }

    public record Deletion(ContactScope scope, UUID contactId, long siteVersion, Instant deletedAt,
            String deletedBy) implements Result {
        public Deletion {
            Objects.requireNonNull(scope, SCOPE);
            Objects.requireNonNull(contactId, "contactId");
            Objects.requireNonNull(deletedAt, "deletedAt");
            OpaqueValue.requireValid(deletedBy, 128);
            if (siteVersion < 1) throw new IllegalArgumentException("site-version-invalid");
        }
    }
}
