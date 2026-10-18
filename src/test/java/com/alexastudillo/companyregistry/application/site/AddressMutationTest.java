package com.alexastudillo.companyregistry.application.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.AddressRecord;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.Audit;
import com.alexastudillo.companyregistry.domain.model.AddressInformationPatch;
import com.alexastudillo.companyregistry.domain.model.AddressType;
import com.alexastudillo.companyregistry.domain.model.FieldChange;
import com.alexastudillo.companyregistry.domain.text.InvalidText;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AddressMutationTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final AddressScope SCOPE = new AddressScope(TENANT, UUID.randomUUID(), UUID.randomUUID());
    private static final OperationContext CONTEXT = new OperationContext(TENANT, "Actor-aB", UUID.randomUUID().toString());
    private static final UUID ADDRESS = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-18T20:00:00.123456Z");

    @Test
    void requiresCreationKeyButAllowsOmissionForUpdateAndDelete() {
        var create = intent(AddressMutation.Operation.CREATE);
        assertThrows(InvalidText.class, () -> new AddressMutation.Command(CONTEXT, SCOPE, 0, null, create));
        assertEquals(" opaque Key ", new AddressMutation.Command(CONTEXT, SCOPE, 0, " opaque Key ", create).idempotencyKey());
        for (var operation : new AddressMutation.Operation[] {AddressMutation.Operation.UPDATE, AddressMutation.Operation.DELETE}) {
            var intent = intent(operation);
            assertEquals(intent, new AddressMutation.Command(CONTEXT, SCOPE, 0, null, intent).intent());
            assertThrows(InvalidText.class, () -> new AddressMutation.Command(CONTEXT, SCOPE, 0, " ", intent));
        }
    }

    @Test
    void validatesVersionAndQualifiedTenant() {
        var intent = intent(AddressMutation.Operation.UPDATE);
        assertThrows(IllegalArgumentException.class, () -> new AddressMutation.Command(CONTEXT, SCOPE, -1, null, intent));
        var foreign = new AddressScope(UUID.randomUUID(), SCOPE.companyId(), SCOPE.siteId());
        assertThrows(IllegalArgumentException.class, () -> new AddressMutation.Command(CONTEXT, foreign, 0, null, intent));
        assertEquals(Long.MAX_VALUE, new AddressMutation.Command(CONTEXT, SCOPE, Long.MAX_VALUE, null, intent).expectedVersion());
    }

    @Test
    void intentRequiresRouteIdentityForIndividualCommandsAndNoDeleteContent() {
        var empty = AddressInformationPatch.empty();
        assertThrows(IllegalArgumentException.class, () -> new AddressMutation.Intent(AddressMutation.Operation.CREATE, ADDRESS, empty));
        assertThrows(IllegalArgumentException.class, () -> new AddressMutation.Intent(AddressMutation.Operation.UPDATE, null, empty));
        assertThrows(IllegalArgumentException.class, () -> new AddressMutation.Intent(AddressMutation.Operation.DELETE, null, empty));
        var patch = new AddressInformationPatch(FieldChange.present("MAILING"), empty.text(), empty.isPrimary(), empty.coordinates());
        assertThrows(IllegalArgumentException.class, () -> new AddressMutation.Intent(AddressMutation.Operation.DELETE, ADDRESS, patch));
    }

    @Test
    void resultMustBelongToSiteAndRepresentCurrentAddressOrDeletionReceipt() {
        var audit = new Audit(NOW, "Creator", NOW, CONTEXT.actor());
        var address = new AddressRecord(ADDRESS, SCOPE.siteId(), AddressType.PHYSICAL, "EC", null,
                "A", null, null, null, null, null, false, audit, null, null);
        var result = new AddressMutation.View(SCOPE, address, 7);
        assertEquals(ADDRESS, result.addressId());
        assertEquals(7, result.siteVersion());
        var foreign = new AddressScope(TENANT, SCOPE.companyId(), UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> new AddressMutation.View(foreign, address, 7));
        assertThrows(IllegalArgumentException.class, () -> new AddressMutation.View(SCOPE, address, -1));
        var actor = CONTEXT.actor();
        assertEquals(new AddressMutation.Deletion(SCOPE, ADDRESS, 8, NOW, actor).scope(), result.scope());
        assertThrows(IllegalArgumentException.class, () -> new AddressMutation.Deletion(SCOPE, ADDRESS, 0, NOW, actor));
    }

    @Test
    void selectionUsesClosedTypedFiltersAndBoundedPagination() {
        var filters = new AddressSelection.Filters(AddressType.MAILING, false);
        assertEquals(filters, new AddressSelection.Request(filters, 1, null).filters());
        assertEquals(200, new AddressSelection.Request(filters, 200, null).limit());
        assertEquals(50, AddressSelection.DEFAULT_LIMIT);
        assertThrows(IllegalArgumentException.class, () -> new AddressSelection.Request(filters, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new AddressSelection.Request(filters, 201, null));
        assertThrows(IllegalArgumentException.class, () -> new AddressSelection.Request(filters, 50, " "));
    }

    private static AddressMutation.Intent intent(AddressMutation.Operation operation) {
        return new AddressMutation.Intent(operation, operation == AddressMutation.Operation.CREATE ? null : ADDRESS,
                AddressInformationPatch.empty());
    }
}
