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
class CountryReferenceAdapterTest {
    @Inject CountryReferences countries;
    @Test void exactCodeStateAndSupportedHeadersAreValidated() {
        var context = new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString());
        var id = UUID.randomUUID(); var stub = ReferenceStubResource.stub; stub.reset();
        for (var state : GeographicState.values()) {
            stub.reply("/api/v1/countries/by-alpha2/EC", 200, body(id, "EC", state.name()));
            assertEquals(new CountryReference(id, "EC", state), countries.find(context, " ec ").await().atMost(Duration.ofSeconds(5)));
        }
        var request = stub.requests().getFirst();
        assertEquals(context.processId(), request.header("process-id")); assertEquals(context.actor(), request.header("user-id"));
        assertNull(request.header("Tenant-Id")); assertNull(request.header("company-id"));
        stub.reply("/api/v1/countries/by-alpha2/EC", 404, "{\"status\":404,\"code\":\"country-not-found\"}");
        assertNull(countries.find(context, "EC").await().atMost(Duration.ofSeconds(5)));
        var timeout = Duration.ofSeconds(5);
        for (var body : new String[]{body(id, "CO", "ACTIVE"), body(id, "EC", "UNKNOWN"),
                "{\"status\":200,\"code\":\"successful\",\"data\":{\"alpha2Code\":\"EC\",\"status\":\"ACTIVE\"}}",
                "{\"status\":201,\"code\":\"successful\",\"data\":{}}"}) {
            stub.reply("/api/v1/countries/by-alpha2/EC", 200, body);
            var awaitable = countries.find(context, "EC").await();
            var failure = assertThrows(ReferenceFailure.class, () -> awaitable.atMost(timeout));
            assertEquals(ReferenceFailure.Kind.INVALID_CONTRACT, failure.kind());
        }
    }
    static String body(UUID id, String code, String state) {
        return "{\"status\":200,\"code\":\"successful\",\"data\":{\"id\":\"" + id + "\",\"alpha2Code\":\"" + code + "\",\"status\":\"" + state + "\",\"defaultName\":\"ignored\"}}";
    }
}
