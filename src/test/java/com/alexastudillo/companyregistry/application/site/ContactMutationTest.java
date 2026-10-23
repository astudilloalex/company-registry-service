package com.alexastudillo.companyregistry.application.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.ContactRecord;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.Audit;
import com.alexastudillo.companyregistry.domain.model.ContactInformationPatch;
import com.alexastudillo.companyregistry.domain.model.ContactType;
import com.alexastudillo.companyregistry.domain.model.FieldChange;
import com.alexastudillo.companyregistry.domain.text.InvalidText;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContactMutationTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final ContactScope SCOPE = new ContactScope(TENANT, UUID.randomUUID(), UUID.randomUUID());
    private static final OperationContext CONTEXT = new OperationContext(TENANT, "Actor-aB", UUID.randomUUID().toString());
    private static final UUID CONTACT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-18T20:00:00.123456Z");

    @Test
    void requiresCreationKeyButAllowsOmissionForUpdateAndDelete() {
        var create = intent(ContactMutation.Operation.CREATE);
        assertThrows(InvalidText.class, () -> new ContactMutation.Command(CONTEXT, SCOPE, 0, null, create));
        assertEquals(" opaque Key ", new ContactMutation.Command(CONTEXT, SCOPE, 0, " opaque Key ", create).idempotencyKey());
        for (var operation : new ContactMutation.Operation[] {ContactMutation.Operation.UPDATE, ContactMutation.Operation.DELETE}) {
            var intent = intent(operation);
            assertEquals(intent, new ContactMutation.Command(CONTEXT, SCOPE, 0, null, intent).intent());
            assertThrows(InvalidText.class, () -> new ContactMutation.Command(CONTEXT, SCOPE, 0, " ", intent));
        }
    }

    @Test
    void validatesVersionAndQualifiedTenant() {
        var intent = intent(ContactMutation.Operation.UPDATE);
        assertThrows(IllegalArgumentException.class, () -> new ContactMutation.Command(CONTEXT, SCOPE, -1, null, intent));
        var foreign = new ContactScope(UUID.randomUUID(), SCOPE.companyId(), SCOPE.siteId());
        assertThrows(IllegalArgumentException.class, () -> new ContactMutation.Command(CONTEXT, foreign, 0, null, intent));
        assertEquals(Long.MAX_VALUE, new ContactMutation.Command(CONTEXT, SCOPE, Long.MAX_VALUE, null, intent).expectedVersion());
    }

    @Test
    void intentRequiresRouteIdentityForIndividualCommandsAndNoDeleteContent() {
        var empty = ContactInformationPatch.empty();
        assertThrows(IllegalArgumentException.class, () -> new ContactMutation.Intent(ContactMutation.Operation.CREATE, CONTACT, empty));
        assertThrows(IllegalArgumentException.class, () -> new ContactMutation.Intent(ContactMutation.Operation.UPDATE, null, empty));
        assertThrows(IllegalArgumentException.class, () -> new ContactMutation.Intent(ContactMutation.Operation.DELETE, null, empty));
        var patch = new ContactInformationPatch(FieldChange.present("WHATSAPP"), empty.value(), empty.label(), empty.isPrimary());
        assertThrows(IllegalArgumentException.class, () -> new ContactMutation.Intent(ContactMutation.Operation.DELETE, CONTACT, patch));
    }

    @Test
    void resultMustBelongToSiteAndRepresentCurrentContactOrDeletionReceipt() {
        var audit = new Audit(NOW, "Creator", NOW, CONTEXT.actor());
        var contact = new ContactRecord(CONTACT, SCOPE.siteId(), ContactType.EMAIL, "a@example.com", null, false, audit, null, null);
        var result = new ContactMutation.View(SCOPE, contact, 7);
        assertEquals(CONTACT, result.contactId());
        assertEquals(7, result.siteVersion());
        var foreign = new ContactScope(TENANT, SCOPE.companyId(), UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> new ContactMutation.View(foreign, contact, 7));
        assertThrows(IllegalArgumentException.class, () -> new ContactMutation.View(SCOPE, contact, -1));
        var actor = CONTEXT.actor();
        assertEquals(new ContactMutation.Deletion(SCOPE, CONTACT, 8, NOW, actor).scope(), result.scope());
        assertThrows(IllegalArgumentException.class, () -> new ContactMutation.Deletion(SCOPE, CONTACT, 0, NOW, actor));
    }

    @Test
    void selectionUsesClosedTypedFiltersAndBoundedPagination() {
        var filters = new ContactSelection.Filters(ContactType.WHATSAPP, false);
        assertEquals(filters, new ContactSelection.Request(filters, 1, null).filters());
        assertEquals(200, new ContactSelection.Request(filters, 200, null).limit());
        assertEquals(50, ContactSelection.DEFAULT_LIMIT);
        assertThrows(IllegalArgumentException.class, () -> new ContactSelection.Request(filters, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ContactSelection.Request(filters, 201, null));
        assertThrows(IllegalArgumentException.class, () -> new ContactSelection.Request(filters, 50, " "));
    }

    private static ContactMutation.Intent intent(ContactMutation.Operation operation) {
        return new ContactMutation.Intent(operation, operation == ContactMutation.Operation.CREATE ? null : CONTACT,
                ContactInformationPatch.empty());
    }
}
