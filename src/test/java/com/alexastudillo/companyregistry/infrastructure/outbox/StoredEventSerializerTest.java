package com.alexastudillo.companyregistry.infrastructure.outbox;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.outbox.DeliveryFailure;
import com.alexastudillo.companyregistry.application.outbox.StoredEvent;
import com.alexastudillo.companyregistry.contract.OutboxFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaRegistry;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StoredEventSerializerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final StoredEventSerializer serializer = new StoredEventSerializer(json);

    @Test void allSevenContractsAndArchivedSnapshotAreStableAndMatchPackagedSchemas() throws Exception {
        var names = new java.util.ArrayList<>(OutboxDestination.EVENT_TYPES);
        names.add("company.updated.v1.archived");
        names.add("company.site.updated.v1.child");
        for (var name : names) {
            var fact = OutboxFixtures.example(name);
            byte[] first = serializer.serialize(fact);
            assertArrayEquals(first, serializer.serialize(fact));
            var wire = json.readTree(first);
            assertEquals(11, wire.size());
            assertTrue(wire.has("causationId") && wire.path("causationId").isNull());
            assertEquals(fact.payload(), json.convertValue(wire.path("payload"), Map.class));
            try (var resource = getClass().getResourceAsStream("/META-INF/contracts/events/" + fact.kind().type() + ".schema.json")) {
                assertNotNull(resource);
                var schema = SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                        .getSchema(new String(resource.readAllBytes(), StandardCharsets.UTF_8), com.networknt.schema.InputFormat.JSON);
                assertTrue(schema.validate(wire).isEmpty(), () -> schema.validate(wire).toString());
            }
        }
    }

    @Test void rejectsSensitiveExtraPropertiesAndContractViolationsWithoutRetainingPrivateCauses() {
        var original = OutboxFixtures.example("company.created.v1");
        for (var field : new String[]{"email", "phone", "legalIdentifier", "coordinates", "address"}) {
            var payload = new LinkedHashMap<>(original.payload());
            payload.put(field, "password=private-value");
            assertInvalid(withPayload(original, payload));
        }
        for (var entry : Map.of("companyId", "00000000-0000-4000-8000-000000000000", "createdAt", "2026-01-01T00:00:00Z",
                "status", "ACTIVE", "code", "x".repeat(65), "partyId", "invalid").entrySet()) {
            var payload = new LinkedHashMap<>(original.payload()); payload.put(entry.getKey(), entry.getValue());
            assertInvalid(withPayload(original, payload));
        }
        var bad = new StoredEvent(original.eventId(), original.tenantId(), original.aggregate(),
                new StoredEvent.Kind(original.kind().type(), (short) 2, original.kind().occurredAt()), original.context(), original.payload());
        var failure = assertThrows(DeliveryFailure.class, () -> serializer.serialize(bad));
        assertEquals(DeliveryFailure.Code.UNSUPPORTED_EVENT, failure.code());
    }

    private void assertInvalid(StoredEvent fact) {
        var failure = assertThrows(DeliveryFailure.class, () -> serializer.serialize(fact));
        assertEquals("invalid-contract", failure.getMessage());
        assertNull(failure.getCause());
    }
    private static StoredEvent withPayload(StoredEvent e, Map<String, Object> payload) {
        return new StoredEvent(e.eventId(), e.tenantId(), e.aggregate(), e.kind(), e.context(), payload);
    }
}
