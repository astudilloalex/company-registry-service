package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alexastudillo.companyregistry.application.company.CompanyLifecycle;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanyLifecycleCommandTest {
    private final OperationContext context = new OperationContext(UUID.randomUUID(), "actor", UUID.randomUUID().toString());
    private final UUID company = UUID.randomUUID();

    @Test
    void keepsThreeNamespacesAndOptionalOpaqueKey() {
        assertEquals(3, Arrays.stream(CompanyLifecycle.Operation.values()).map(CompanyLifecycle.Operation::namespace).distinct().count());
        for (var operation : CompanyLifecycle.Operation.values()) {
            var command = new CompanyLifecycle.Command(context, company, Long.MAX_VALUE, " key ", operation);
            assertEquals(" key ", command.idempotencyKey());
            assertEquals(operation, command.operation());
            assertEquals(Long.MAX_VALUE, command.expectedVersion());
        }
        assertNull(new CompanyLifecycle.Command(context, company, 0, null, CompanyLifecycle.Operation.ACTIVATE).idempotencyKey());
    }

    @Test
    void rejectsInvalidConstruction() {
        var operation = CompanyLifecycle.Operation.ACTIVATE;
        assertThrows(NullPointerException.class, () -> new CompanyLifecycle.Command(null, company, 0, null, operation));
        assertThrows(NullPointerException.class, () -> new CompanyLifecycle.Command(context, null, 0, null, operation));
        assertThrows(NullPointerException.class, () -> new CompanyLifecycle.Command(context, company, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CompanyLifecycle.Command(context, company, -1, null, operation));
        assertThrows(IllegalArgumentException.class, () -> new CompanyLifecycle.Command(context, company, 0, " ", operation));
        String tooLong = "x".repeat(129);
        assertThrows(IllegalArgumentException.class, () -> new CompanyLifecycle.Command(context, company, 0, tooLong, operation));
    }
}
