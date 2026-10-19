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
class DivisionReferenceAdapterTest {
    @Inject DivisionReferences divisions;
    @Inject CountryReferences countries;

    @Test
    void resolvedCountryEntryMakesExactlyOneCountryAndOneDivisionRequest() {
        var context = new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString());
        var countryId = UUID.randomUUID();
        var divisionId = UUID.randomUUID();
        var stub = ReferenceStubResource.stub;
        stub.reset();
        var countryPath = "/api/v1/countries/by-alpha2/EC";
        var divisionPath = "/api/v1/countries/" + countryId + "/administrative-divisions/by-canonical-code/EC-P";
        stub.reply(countryPath, 200, CountryReferenceAdapterTest.body(countryId, "EC", "ACTIVE"));
        stub.reply(divisionPath, 200, body(divisionId, countryId, "EC-P", "ACTIVE"));
        var result = countries.find(context, "EC")
                .chain(country -> divisions.findInCountry(context, country, " ec-p "))
                .await().atMost(Duration.ofSeconds(5));
        assertEquals(new DivisionReference(divisionId, countryId, "EC-P", GeographicState.ACTIVE, GeographicState.ACTIVE), result);
        assertEquals(2, stub.requests().size());
        assertEquals(countryPath, stub.requests().get(0).path());
        assertEquals(divisionPath, stub.requests().get(1).path());
    }

    @Test
    void resolvedCountryEntryValidatesAllStatesIdentityHeadersAbsenceAndFailures() {
        var context = new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString());
        var country = new CountryReference(UUID.randomUUID(), "EC", GeographicState.ACTIVE);
        var divisionId = UUID.randomUUID();
        var stub = ReferenceStubResource.stub;
        stub.reset();
        var path = "/api/v1/countries/" + country.id() + "/administrative-divisions/by-canonical-code/EC-P";
        for (var state : GeographicState.values()) {
            stub.reply(path, 200, body(divisionId, country.id(), "EC-P", state.name()));
            var result = divisions.findInCountry(context, country, "ec-p").await().atMost(Duration.ofSeconds(5));
            assertEquals(state, result.state());
            assertEquals(state == GeographicState.ACTIVE, result.active());
        }
        for (var request : stub.requests()) {
            assertEquals(path, request.path());
            assertEquals(context.actor(), request.header("user-id"));
            assertEquals(context.processId(), request.header("process-id"));
            assertNull(request.header("company-id"));
            assertNull(request.header("tenant-id"));
        }
        stub.reply(path, 404, "{}");
        assertNull(divisions.findInCountry(context, country, "EC-P").await().atMost(Duration.ofSeconds(5)));
        for (var invalid : new String[] {body(divisionId, UUID.randomUUID(), "EC-P", "ACTIVE"),
                body(divisionId, country.id(), "EC-X", "ACTIVE"), body(divisionId, country.id(), "EC-P", "UNKNOWN"),
                "{invalid-provider-json"}) {
            stub.reply(path, 200, invalid);
            assertResolvedFailure(context, country, ReferenceFailure.Kind.INVALID_CONTRACT);
        }
        stub.reply(path, 503, "{}");
        assertResolvedFailure(context, country, ReferenceFailure.Kind.UNAVAILABLE);
        stub.reply(path, new com.alexastudillo.companyregistry.contract.ReferenceStubServer.Reply(200, "{}", 800));
        assertResolvedFailure(context, country, ReferenceFailure.Kind.TIMEOUT);
    }

    private void assertResolvedFailure(OperationContext context, CountryReference country, ReferenceFailure.Kind kind) {
        var awaitable = divisions.findInCountry(context, country, "EC-P").await();
        var timeout = Duration.ofSeconds(5);
        var failure = assertThrows(ReferenceFailure.class, () -> awaitable.atMost(timeout));
        assertEquals(kind, failure.kind());
        assertNull(failure.getCause());
    }
    @Test void resolvesCountryBeforeDivisionAndValidatesMembershipCodeAndActivity() {
        var context = new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString());
        var country = UUID.randomUUID(); var id = UUID.randomUUID(); var stub = ReferenceStubResource.stub; stub.reset();
        var countryPath = "/api/v1/countries/by-alpha2/EC";
        var divisionPath = "/api/v1/countries/" + country + "/administrative-divisions/by-canonical-code/EC-P";
        stub.reply(countryPath, 200, CountryReferenceAdapterTest.body(country, "EC", "ACTIVE"));
        for (var state : GeographicState.values()) {
            stub.reply(divisionPath, 200, body(id, country, "EC-P", state.name()));
            assertEquals(new DivisionReference(id, country, "EC-P", state, GeographicState.ACTIVE),
                    divisions.find(context, "EC", " ec-p ").await().atMost(Duration.ofSeconds(5)));
        }
        assertEquals(countryPath, stub.requests().get(0).path()); assertEquals(divisionPath, stub.requests().get(1).path());
        for (var request : stub.requests()) {
            assertEquals(context.processId(), request.header("process-id")); assertEquals(context.actor(), request.header("user-id"));
            assertNull(request.header("company-id")); assertNull(request.header("Tenant-Id"));
        }
        var timeout = Duration.ofSeconds(5);
        for (var invalid : new String[]{body(id, UUID.randomUUID(), "EC-P", "ACTIVE"), body(id, country, "EC-X", "ACTIVE"), body(id, country, "EC-P", "UNKNOWN")}) {
            stub.reply(divisionPath, 200, invalid);
            var awaitable = divisions.find(context, "EC", "EC-P").await();
            var failure = assertThrows(ReferenceFailure.class, () -> awaitable.atMost(timeout));
            assertEquals(ReferenceFailure.Kind.INVALID_CONTRACT, failure.kind());
        }
        stub.reply(divisionPath, 404, "{\"status\":404,\"code\":\"administrative-division-not-found\"}");
        assertNull(divisions.find(context, "EC", "EC-P").await().atMost(Duration.ofSeconds(5)));
        stub.reply(countryPath, 200, CountryReferenceAdapterTest.body(country, "EC", "RETIRED"));
        stub.reply(divisionPath, 200, body(id, country, "EC-P", "ACTIVE"));
        var inactive = divisions.find(context, "EC", "EC-P").await().atMost(Duration.ofSeconds(5));
        assertEquals(GeographicState.RETIRED, inactive.countryState()); assertFalse(inactive.active());
        stub.reset();
        assertNull(divisions.find(context, "EC", "EC-P").await().atMost(Duration.ofSeconds(5)));
        assertEquals(1, stub.requests().size(), "Absent country must not trigger division lookup");
    }
    static String body(UUID id, UUID country, String code, String state) {
        return "{\"status\":200,\"code\":\"successful\",\"data\":{\"id\":\"" + id + "\",\"countryId\":\"" + country
                + "\",\"canonicalCode\":\"" + code + "\",\"status\":\"" + state + "\",\"defaultName\":\"ignored\"}}";
    }
}
