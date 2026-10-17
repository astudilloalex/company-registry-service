package com.alexastudillo.companyregistry.infrastructure.reference;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.reference.*;
import com.alexastudillo.companyregistry.application.reference.References.*;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class) @QuarkusTestResource(ReferenceStubResource.class)
class PartyReferenceAdapterTest {
    @Inject PartyReferences parties;
    @Test void tenantContextProjectionAbsenceTypeAndStateAreDistinct() {
        var context = new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT));
        var id = UUID.randomUUID(); var path = "/v1/parties/" + id; var stub = ReferenceStubResource.stub; stub.reset();
        for (var state : PartyState.values()) {
            stub.reply(path, 200, body(id, "LEGAL_ENTITY", state.name()));
            var result = parties.find(context, id).await().atMost(Duration.ofSeconds(5));
            assertEquals(new PartyReference(id, PartyKind.LEGAL_ENTITY, state), result);
            assertTrue(result.legalEntity()); assertEquals(state == PartyState.ACTIVE, result.active());
        }
        var request = stub.requests().getFirst();
        assertEquals(context.tenantId().toString(), request.header("Tenant-Id"));
        assertEquals(context.actor(), request.header("User-Id")); assertEquals(context.processId(), request.header("Process-Id"));
        stub.reply(path, 200, body(id, "NATURAL_PERSON", "ACTIVE"));
        assertFalse(parties.find(context, id).await().atMost(Duration.ofSeconds(5)).legalEntity());
        stub.reply(path, 404, "{\"status\":404,\"code\":\"party-not-found\"}");
        assertNull(parties.find(context, id).await().atMost(Duration.ofSeconds(5)));
        var timeout = Duration.ofSeconds(5);
        for (var body : new String[]{body(UUID.randomUUID(), "LEGAL_ENTITY", "ACTIVE"), body(id, "UNKNOWN", "ACTIVE"),
                body(id, "LEGAL_ENTITY", "UNKNOWN"), "{\"status\":200,\"code\":\"successful\"}"}) {
            stub.reply(path, 200, body);
            var awaitable = parties.find(context, id).await();
            var failure = assertThrows(ReferenceFailure.class, () -> awaitable.atMost(timeout));
            assertEquals(ReferenceFailure.Kind.INVALID_CONTRACT, failure.kind());
        }
    }
    private static String body(UUID id, String type, String state) {
        return "{\"status\":200,\"code\":\"successful\",\"data\":{\"partyId\":\"" + id + "\",\"type\":\"" + type
                + "\",\"recordStatus\":\"" + state + "\",\"legalEntityDetails\":{\"private\":\"ignored\"}}}";
    }
}
