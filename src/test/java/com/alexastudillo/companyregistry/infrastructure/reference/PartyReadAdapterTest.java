package com.alexastudillo.companyregistry.infrastructure.reference;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.company.PartyReads;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.reference.ReferenceFailure;
import com.alexastudillo.companyregistry.contract.ReferenceStubServer;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
@QuarkusTestResource(ReferenceStubResource.class)
class PartyReadAdapterTest {
    @Inject
    PartyReads reads;
    private final OperationContext context = new OperationContext(UUID.randomUUID(), "Actor-aB",
            UUID.randomUUID().toString());
    private static final Duration WAIT = Duration.ofSeconds(5);

    static String detail(UUID id, String type, String state) {
        return com.alexastudillo.companyregistry.contract.CompanyProviderFixtures.detail(id, type, state);
    }

    static String identifier(UUID id, UUID identifier, String status) {
        return com.alexastudillo.companyregistry.contract.CompanyProviderFixtures.identifier(id, identifier, status);
    }

    static String page(String data, int size, String next) {
        return com.alexastudillo.companyregistry.contract.CompanyProviderFixtures.page(data, size, next);
    }

    @Test
    void completeDetailAndEveryIdentifierPagePreservePublicFieldsAndContext() {
        var stub = ReferenceStubResource.stub;
        stub.reset();
        var id = UUID.randomUUID();
        var path = "/v1/parties/" + id;
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        stub.reply(path, 200, detail(id, "LEGAL_ENTITY", "INACTIVE"));
        stub.reply(path + "/identifiers", 200,
                com.alexastudillo.companyregistry.contract.CompanyProviderFixtures.fullFirstPage(id, first, "next"));
        stub.reply(path + "/identifiers?limit=200&cursor=next", 200,
                com.alexastudillo.companyregistry.contract.CompanyProviderFixtures
                        .page(identifier(id, second, "REVOKED"), 1, null, 201, 2));
        var value = reads.find(context, id).await().atMost(WAIT);
        assertEquals("INACTIVE", value.recordStatus());
        assertEquals(3, value.version());
        assertEquals("Original-aB", value.createdBy());
        assertEquals("2020-01-15", value.legalEntityDetails().incorporatedOn().toString());
        assertNull(value.legalEntityDetails().dissolvedOn());
        assertEquals("SAS", value.legalEntityDetails().legalFormCode());
        assertEquals(201, value.identifiers().size());
        assertEquals(first, value.identifiers().getFirst().identifierId());
        assertEquals(second, value.identifiers().getLast().identifierId());
        assertTrue(value.identifiers().getFirst().isPrimary());
        assertEquals("*********0001", value.identifiers().getFirst().maskedValue());
        for (var request : stub.requests()) {
            assertEquals(context.tenantId().toString(), request.header("Tenant-Id"));
            assertEquals(context.actor(), request.header("User-Id"));
            assertEquals(context.processId(), request.header("Process-Id"));
            if (request.path().endsWith("identifiers"))
                assertTrue(request.query().contains("limit=200"));
        }
    }

    @Test
    void statesAbsenceNaturalPersonAndConfirmedEmptyCollectionAreDistinct() {
        var stub = ReferenceStubResource.stub;
        stub.reset();
        var id = UUID.randomUUID();
        var path = "/v1/parties/" + id;
        for (var state : List.of("DRAFT", "ACTIVE", "INACTIVE", "ARCHIVED")) {
            stub.reply(path, 200, detail(id, "LEGAL_ENTITY", state));
            stub.reply(path + "/identifiers", 200, page("", 0, null));
            var value = reads.find(context, id).await().atMost(WAIT);
            assertEquals(state, value.recordStatus());
            assertTrue(value.identifiers().isEmpty());
        }
        stub.reply(path, 200, detail(id, "NATURAL_PERSON", "ACTIVE"));
        assertEquals("NATURAL_PERSON", reads.find(context, id).await().atMost(WAIT).type());
        stub.reply(path, 404, "{}");
        assertNull(reads.find(context, id).await().atMost(WAIT));
        stub.reply(path, 200, detail(id, "LEGAL_ENTITY", "ACTIVE"));
        for (var status : List.of("PENDING_VERIFICATION", "VERIFIED", "REJECTED", "EXPIRED", "REVOKED")) {
            stub.reply(path + "/identifiers", 200, page(identifier(id, UUID.randomUUID(), status), 1, null));
            assertEquals(status, reads.find(context, id).await().atMost(WAIT).identifiers().getFirst().status());
        }
        stub.reset();
        assertTrue(reads.findAll(context, List.of()).await().atMost(WAIT).isEmpty());
        assertTrue(stub.requests().isEmpty());
    }

    @Test
    void rejectsWrongIdentityOwnershipRepeatedCursorsAndDuplicatesWithoutPartialResults() {
        var stub = ReferenceStubResource.stub;
        stub.reset();
        var id = UUID.randomUUID();
        var path = "/v1/parties/" + id;
        stub.reply(path, 200, detail(UUID.randomUUID(), "LEGAL_ENTITY", "ACTIVE"));
        assertFailure(id, ReferenceFailure.Kind.INVALID_CONTRACT);
        stub.reply(path, 200, detail(id, "LEGAL_ENTITY", "ACTIVE"));
        for (var invalid : List.of(detail(id, "LEGAL_ENTITY", "ACTIVE").replace("\"version\":3", "\"version\":\"3\""),
                detail(id, "LEGAL_ENTITY", "ACTIVE").replace("\"displayName\":\"COMPAÑÍA\"", "\"displayName\":123"))) {
            stub.reply(path, 200, invalid);
            assertFailure(id, ReferenceFailure.Kind.INVALID_CONTRACT);
        }
        stub.reply(path, 200, detail(id, "LEGAL_ENTITY", "ACTIVE"));
        for (var bad : List.of(identifier(UUID.randomUUID(), UUID.randomUUID(), "VERIFIED"),
                identifier(id, UUID.randomUUID(), "UNKNOWN"))) {
            stub.reply(path + "/identifiers", 200, page(bad, 1, null));
            assertFailure(id, ReferenceFailure.Kind.INVALID_CONTRACT);
        }
        stub.reply(path + "/identifiers", 200, page(
                identifier(id, UUID.randomUUID(), "VERIFIED").replace("\"isPrimary\":true", "\"isPrimary\":\"true\""),
                1, null));
        assertFailure(id, ReferenceFailure.Kind.INVALID_CONTRACT);
        var item = identifier(id, UUID.randomUUID(), "VERIFIED");
        stub.reply(path + "/identifiers", 200, page(item, 1, "next"));
        stub.reply(path + "/identifiers?limit=200&cursor=next", 200, page(item, 1, null));
        assertFailure(id, ReferenceFailure.Kind.INVALID_CONTRACT);
        stub.reply(path + "/identifiers?limit=200&cursor=next", 200, page("", 0, "next"));
        assertFailure(id, ReferenceFailure.Kind.INVALID_CONTRACT);
    }

    @Test
    void laterPageTimeoutAndHttpFailuresKeepTheirCategories() {
        var stub = ReferenceStubResource.stub;
        stub.reset();
        var id = UUID.randomUUID();
        var path = "/v1/parties/" + id;
        stub.reply(path, 200, detail(id, "LEGAL_ENTITY", "ACTIVE"));
        stub.reply(path + "/identifiers", 200, page("", 0, "next"));
        stub.reply(path + "/identifiers?limit=200&cursor=next",
                new ReferenceStubServer.Reply(200, page("", 0, null), 500));
        assertFailure(id, ReferenceFailure.Kind.TIMEOUT);
        for (var status : List.of(429, 500, 503)) {
            stub.reply(path, status, "{}");
            assertFailure(id, ReferenceFailure.Kind.UNAVAILABLE);
        }
        stub.reply(path, 200, "{invalid");
        assertFailure(id, ReferenceFailure.Kind.INVALID_CONTRACT);
    }

    private void assertFailure(UUID id, ReferenceFailure.Kind kind) {
        var awaitable = reads.find(context, id).await();
        var failure = assertThrows(ReferenceFailure.class, () -> awaitable.atMost(WAIT));
        assertEquals(kind, failure.kind());
    }
}
