package com.alexastudillo.companyregistry.infrastructure.reference;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.reference.*;
import com.alexastudillo.companyregistry.contract.ReferenceStubServer;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class) @QuarkusTestResource(ReferenceStubResource.class)
class ReferenceFailureTest {
    @Inject PartyReferences parties;
    @Inject CountryReferences countries;

    @Test void classifiesRealProviderTimeoutStatusAndInvalidJsonWithoutReturningAbsence() {
        var stub = ReferenceStubResource.stub; var id = UUID.randomUUID();
        var context = new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString());
        for (boolean party : new boolean[]{true, false}) {
            var path = party ? "/v1/parties/" + id : "/api/v1/countries/by-alpha2/EC";
            stub.reset(); stub.reply(path, 503, "{\"status\":503,\"code\":\"unavailable\"}");
            failure(call(party, context, id), ReferenceFailure.Kind.UNAVAILABLE);
            stub.reply(path, 200, "{invalid-private-provider-json");
            failure(call(party, context, id), ReferenceFailure.Kind.INVALID_CONTRACT);
            stub.reply(path, 200, "{\"status\":200,\"code\":\"wrong-code\",\"data\":{}}");
            failure(call(party, context, id), ReferenceFailure.Kind.INVALID_CONTRACT);
            stub.reply(path, new ReferenceStubServer.Reply(200, "{}", 800));
            failure(call(party, context, id), ReferenceFailure.Kind.TIMEOUT);
        }
    }

    private Uni<?> call(boolean party, OperationContext context, UUID id) {
        return party ? parties.find(context, id) : countries.find(context, "EC");
    }
    private static void failure(Uni<?> operation, ReferenceFailure.Kind kind) {
        var awaitable = operation.await();
        var timeout = Duration.ofSeconds(4);
        var failure = assertThrows(ReferenceFailure.class, () -> awaitable.atMost(timeout));
        assertEquals(kind, failure.kind()); assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("private-provider"));
    }

    @Test void unavailableNetworkIsNotAbsence() throws Exception {
        var server = new java.net.ServerSocket(0); int port = server.getLocalPort(); server.close();
        var client = org.eclipse.microprofile.rest.client.RestClientBuilder.newBuilder().baseUri(java.net.URI.create("http://127.0.0.1:" + port))
                .connectTimeout(200, java.util.concurrent.TimeUnit.MILLISECONDS).readTimeout(200, java.util.concurrent.TimeUnit.MILLISECONDS).build(PartyClient.class);
        try {
            var adapter = new PartyReferenceAdapter(client);
            failure(adapter.find(new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString()), UUID.randomUUID()), ReferenceFailure.Kind.UNAVAILABLE);
            var enriched = new PartyReadAdapter(client, Duration.ofSeconds(2), 4);
            failure(enriched.find(new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString()), UUID.randomUUID()), ReferenceFailure.Kind.UNAVAILABLE);
        } finally { ((AutoCloseable) client).close(); }
    }
}
