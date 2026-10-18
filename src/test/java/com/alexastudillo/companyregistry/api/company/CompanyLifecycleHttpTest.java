package com.alexastudillo.companyregistry.api.company;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.contract.ReferenceStubServer;
import io.quarkus.test.junit.QuarkusTest;
import java.util.*;
import org.junit.jupiter.api.Test;

@QuarkusTest
@io.quarkus.test.junit.TestProfile(LifecycleHttpProfile.class)
class CompanyLifecycleHttpTest extends LifecycleHttpSupport {
    @Test void invalidOrRepeatedBusinessContextPrecedesAnAcceptedReplay() throws Exception {
        var parent = parent("DRAFT");
        command(parent, "archive", 0, "context", null).then().statusCode(200);
        for (String field : List.of("Process-Id", "Tenant-Id", "User-Id")) {
            var headers = new LinkedHashMap<String, String>();
            headers.put("Process-Id", process); headers.put("Tenant-Id", tenant.toString()); headers.put("User-Id", "Actor-aB");
            headers.put("If-Match", "0"); headers.put("Idempotency-Key", "context");
            var original = headers.remove(field);
            String code = field.toLowerCase(Locale.ROOT) + "-invalid";
            given().headers(headers).contentType("application/json").post(parent.path() + "/archive")
                    .then().statusCode(400).body("size()", equalTo(2), "code", equalTo(code));
            given().headers(headers).header(field, original, original).contentType("application/json")
                    .post(parent.path() + "/archive").then().statusCode(400).body("size()", equalTo(2), "code", equalTo(code));
            for (String value : field.equals("User-Id") ? List.of(" ", "x".repeat(129))
                    : List.of("invalid", "018F1234-5678-7000-8000-aaaaaaaaaaaa")) {
                given().headers(headers).header(field, value).contentType("application/json").body("{}")
                        .post(parent.path() + "/archive").then().statusCode(400).body("size()", equalTo(2), "code", equalTo(code));
            }
        }
        given().header("Tenant-Id", tenant).header("User-Id", "x".repeat(128)).header("Process-Id", process)
                .header("If-Match", "0").header("Idempotency-Key", "context").contentType("application/json")
                .post(parent.path() + "/archive").then().statusCode(200).header("Process-Id", process);
        assertTrue(LifecycleReferenceStubResource.stub.requests().isEmpty());
    }
    @Test
    void ownershipIsIndistinguishableFromAbsenceBeforeVersionStateOrParty() throws Exception {
        var foreign = parent("ACTIVE");
        for (String operation : List.of("activate", "deactivate", "archive")) {
            given().header("Tenant-Id", UUID.randomUUID()).header("User-Id", "Actor-aB").header("Process-Id", process)
                    .contentType("application/json")
                    .header("If-Match", "99").post(foreign.path() + "/" + operation).then().statusCode(404)
                    .body("code", equalTo("company-not-found"), "size()", equalTo(2));
            var absent = new Parent(UUID.randomUUID(), UUID.randomUUID());
            error(command(absent, operation, 99, null, null), 404, "company-not-found");
        }
        assertTrue(LifecycleReferenceStubResource.stub.requests().isEmpty());
    }

    @Test
    void ignoresClosedHistoryAndAcceptsReplacementWithoutAddressOrContact() throws Exception {
        var parent = parent("DRAFT");
        String old = site(parent, "HEADQUARTERS", "OLD");
        request().header("If-Match", "0").post(parent.path() + "/sites/" + old + "/close").then().statusCode(200);
        error(command(parent, "activate", 0, null, null), 409, "legal-headquarters-required");
        headquarters(parent);
        command(parent, "activate", 0, null, null).then().statusCode(200);
        assertEquals("0", scalar(
                "SELECT count(*) FROM company_site_addresses a JOIN company_sites s ON a.site_id=s.id WHERE s.company_id='"
                        + parent.id() + "'"));
        assertEquals("0", scalar(
                "SELECT count(*) FROM company_site_contact_points c JOIN company_sites s ON c.site_id=s.id WHERE s.company_id='"
                        + parent.id() + "'"));
    }

    @Test
    void partyTransportTimeoutIsBoundedAndLeavesEveryLocalEffectUntouched() throws Exception {
        var parent = parent("DRAFT");
        headquarters(parent);
        var gate = LifecycleReferenceStubResource.stub.holdNext("/v1/parties/" + parent.party());
        long started = System.nanoTime();
        try {
            error(command(parent, "activate", 0, "timeout", null), 503, "dependency-unavailable");
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started)
                    .compareTo(java.time.Duration.ofSeconds(5)) < 0);
            assertEquals("DRAFT:0",
                    scalar("SELECT status||':'||version FROM companies WHERE id='" + parent.id() + "'"));
            assertEquals("0", scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant
                    + "' AND operation='company.activate.v1'"));
        } finally {
            gate.reply().complete(new ReferenceStubServer.Reply(503, "{}", 0));
        }
    }

    @Test
    void fullCycleKeepsSiteIndependentAndReplaysOriginalOperationalResultsAfterArchival() throws Exception {
        var parent = parent("DRAFT");
        String site = headquarters(parent);
        String sitePath = parent.path() + "/sites/" + site;
        scalar("INSERT INTO company_site_addresses(site_id,country_code,address_line_1,created_by,updated_by) VALUES('"
                + site + "','EC','ADDRESS','Creator-aB','Creator-aB') RETURNING id");
        String originalAddresses = scalar(
                "SELECT jsonb_agg(to_jsonb(a) ORDER BY id)::text FROM company_site_addresses a WHERE site_id='" + site
                        + "'");
        scalar("INSERT INTO company_site_contact_points(site_id,contact_type,value,created_by,updated_by) VALUES('"
                + site + "','EMAIL','User@example.com','Creator-aB','Creator-aB') RETURNING id");
        String originalContacts = scalar(
                "SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM company_site_contact_points c WHERE site_id='"
                        + site + "'");
        String originalSite = scalar("SELECT to_jsonb(s)::text FROM company_sites s WHERE id='" + site + "'");
        var active = command(parent, "activate", 0, "first", null).then().statusCode(200)
                .body("code", equalTo("successful"),
                        "data.status", equalTo("ACTIVE"), "data.version", equalTo(1))
                .extract().jsonPath().getMap("data");
        assertEquals(10, active.size());
        assertFalse(active.containsKey("party"));
        assertFalse(active.containsKey("tenantId"));
        assertFalse(active.containsKey("description"));
        assertFalse(active.containsKey("logoUrl"));
        var siteSnapshot = request().header("If-Match", "1").header("Idempotency-Key", "site-noop").body("{}")
                .patch(sitePath)
                .then().statusCode(200).body("data.effectivelyOperational", equalTo(true)).extract().jsonPath()
                .getMap("data");
        command(parent, "deactivate", 1, "first", "{}").then().statusCode(200).body("data.status", equalTo("INACTIVE"),
                "data.version", equalTo(2));
        request().get(sitePath).then().statusCode(200).body("data.effectivelyOperational", equalTo(false));
        command(parent, "activate", 2, "reactivate", "{}").then().statusCode(200).body("data.version", equalTo(3));
        request().get(sitePath).then().statusCode(200).body("data.effectivelyOperational", equalTo(true));
        command(parent, "deactivate", 3, null, null).then().statusCode(200).body("data.version", equalTo(4));
        command(parent, "archive", 4, "first", null).then().statusCode(200).body("data.status", equalTo("ARCHIVED"),
                "data.version", equalTo(5));
        LifecycleReferenceStubResource.stub.reset();
        String replayProcess = UUID.randomUUID().toString();
        given().header("Tenant-Id", tenant).header("User-Id", "Other actor").header("Process-Id", replayProcess)
                .contentType("application/json").header("If-Match", "0")
                .header("Idempotency-Key", "first").body("{}").post(parent.path() + "/activate").then().statusCode(200)
                .header("Process-Id", replayProcess).body("data", equalTo(active));
        request().header("If-Match", "1").header("Idempotency-Key", "site-noop").body("{}").patch(sitePath)
                .then().statusCode(200).body("data", equalTo(siteSnapshot));
        String registrationKey = scalar("SELECT idempotency_key FROM company_idempotency_records WHERE company_id='"
                + parent.id() + "' AND operation='company.site.create.v1'");
        request().header("Idempotency-Key", registrationKey)
                .body("{\"code\":\"HQ\",\"name\":\"SITE\",\"siteType\":\"HEADQUARTERS\"}")
                .post(parent.path() + "/sites").then().statusCode(201)
                .body("data.siteId", equalTo(site), "data.status", equalTo("DRAFT"), "data.version", equalTo(0),
                        "data.effectivelyOperational", equalTo(false));
        assertTrue(LifecycleReferenceStubResource.stub.requests().isEmpty());
        assertEquals(originalSite, scalar("SELECT to_jsonb(s)::text FROM company_sites s WHERE id='" + site + "'"));
        assertEquals(originalAddresses,
                scalar("SELECT jsonb_agg(to_jsonb(a) ORDER BY id)::text FROM company_site_addresses a WHERE site_id='"
                        + site + "'"));
        assertEquals(originalContacts, scalar(
                "SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM company_site_contact_points c WHERE site_id='"
                        + site + "'"));
        assertEquals("ARCHIVED:5", scalar("SELECT status||':'||version FROM companies WHERE id='" + parent.id() + "'"));
        assertEquals("5", scalar("SELECT count(*) FROM company_outbox_events WHERE tenant_id='" + tenant
                + "' AND aggregate_type='COMPANY'"));
    }

    @Test
    void rejectsArchivedWritersAndPreservesTheirOriginalCatalogsIncludingNoOps() throws Exception {
        var parent = parent("DRAFT");
        String sitePath = parent.path() + "/sites/" + site(parent, "BRANCH", "SITE");
        command(parent, "archive", 0, null, "{}").then().statusCode(200);
        for (String operation : List.of("activate", "deactivate", "archive")) {
            error(command(parent, operation, 1, null, null), 409, "company-archived");
            error(command(parent, operation, 0, null, null), 412, "aggregate-version-mismatch");
        }
        request().header("If-Match", "1").body("{}").patch(parent.path()).then().statusCode(409).body("code",
                equalTo("resource-conflict"));
        request().header("Idempotency-Key", "new").body("{\"code\":\"NEW\",\"name\":\"NEW\"}")
                .post(parent.path() + "/sites")
                .then().statusCode(409).body("code", equalTo("site-registration-not-allowed-for-archived-company"));
        request().header("If-Match", "0").body("{}").patch(sitePath).then().statusCode(409).body("code",
                equalTo("site-mutation-not-allowed-for-archived-company"));
        for (String operation : List.of("activate", "deactivate", "close")) {
            request().header("If-Match", "0").post(sitePath + "/" + operation).then().statusCode(409).body("code",
                    equalTo("site-mutation-not-allowed-for-archived-company"));
        }
        assertTrue(LifecycleReferenceStubResource.stub.requests().isEmpty());
    }

    @Test
    void localPreconditionsPrecedePartyAndRejectedKeysStayAvailable() throws Exception {
        var draft = parent("DRAFT");
        error(command(draft, "deactivate", 0, "reusable", null), 409, "company-transition-not-allowed");
        error(command(draft, "activate", 1, null, null), 412, "aggregate-version-mismatch");
        error(command(draft, "activate", 0, null, null), 409, "legal-headquarters-required");
        String hq = site(draft, "HEADQUARTERS", "HQ");
        error(command(draft, "activate", 0, null, null), 409, "active-headquarters-required");
        assertTrue(LifecycleReferenceStubResource.stub.requests().isEmpty());
        request().header("If-Match", "0").post(draft.path() + "/sites/" + hq + "/activate").then().statusCode(200);
        command(draft, "activate", 0, null, null).then().statusCode(200);
        error(command(draft, "activate", 1, "redundant", null), 409, "company-already-in-requested-state");
        error(command(draft, "archive", 1, null, null), 409, "company-transition-not-allowed");
        command(draft, "deactivate", 1, "reusable", null).then().statusCode(200);
        error(command(draft, "deactivate", 2, "redundant", null), 409, "company-already-in-requested-state");
        assertEquals("1", scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant
                + "' AND operation LIKE 'company.deactivate.%'"));
    }

    @Test
    void partyErrorsAreDistinctPropagateContextAndNeverRequestEnrichmentOrGeography() throws Exception {
        var parent = parent("DRAFT");
        headquarters(parent);
        for (String[] sample : new String[][] { { "NATURAL_PERSON", "INACTIVE", "party-must-be-legal-entity" },
                { "LEGAL_ENTITY", "DRAFT", "party-must-be-active" },
                { "LEGAL_ENTITY", "INACTIVE", "party-must-be-active" },
                { "LEGAL_ENTITY", "ARCHIVED", "party-must-be-active" } }) {
            reply(parent, sample[0], sample[1]);
            error(command(parent, "activate", 0, "new", null), 409, sample[2]);
        }
        var stub = LifecycleReferenceStubResource.stub;
        String path = "/v1/parties/" + parent.party();
        stub.reply(path, 404, "{\"status\":404,\"code\":\"party-not-found\"}");
        error(command(parent, "activate", 0, "new", null), 404, "party-reference-not-found");
        stub.reply(path, 503, "{}");
        error(command(parent, "activate", 0, "new", null), 503, "dependency-unavailable");
        stub.reply(path, 200, "{\"status\":200,\"code\":\"successful\",\"data\":{}}");
        error(command(parent, "activate", 0, "new", null), 502, "dependency-response-invalid");
        for (var observed : stub.requests()) {
            assertEquals(path, observed.path());
            assertNull(observed.query());
            assertEquals(tenant.toString(), observed.header("Tenant-Id"));
            assertEquals("Actor-aB", observed.header("User-Id"));
            assertEquals(process, observed.header("Process-Id"));
        }
        assertEquals("DRAFT:0", scalar("SELECT status||':'||version FROM companies WHERE id='" + parent.id() + "'"));
        assertEquals("0", scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant
                + "' AND operation='company.activate.v1'"));
    }

    @Test
    void validatesBoundaryBeforeReplayAndUsesDescriptiveExactErrorBodies() throws Exception {
        var parent = parent("DRAFT");
        command(parent, "archive", 0, "accepted", null).then().statusCode(200);
        for (String operation : List.of("activate", "deactivate", "archive")) {
            for (String[] sample : new String[][] { { "null", "request-body-must-be-object" },
                    { "[]", "request-body-must-be-object" },
                    { "1", "request-body-must-be-object" }, { "\"x\"", "request-body-must-be-object" },
                    { "true", "request-body-must-be-object" },
                    { "{", "request-body-invalid-json" }, { " ", "request-body-invalid-json" },
                    { "{} true", "request-body-invalid-json" },
                    { "{} {}", "request-body-invalid-json" },
                    { "{\"reason\":1}", "request-body-contains-unknown-fields" },
                    { "{\"status\":1,\"status\":2}", "request-body-contains-unknown-fields" } }) {
                error(command(parent, operation, 0, "accepted", sample[0]), 400, sample[1]);
            }
        }
        error(request().post(parent.path() + "/archive"), 400, "if-match-required");
        for (String value : List.of("01", "-1", "+1", "*", "\"0\"", "9223372036854775808", "0 0")) {
            error(request().header("If-Match", value).post(parent.path() + "/archive"), 400, "if-match-invalid");
        }
        error(request().header("If-Match", "0", "1").post(parent.path() + "/archive"), 400, "if-match-duplicated");
        error(request().header("If-Match", "0").header("Idempotency-Key", "a", "b").post(parent.path() + "/archive"),
                400, "idempotency-key-duplicated");
        error(command(parent, "archive", 0, " ", null), 400, "idempotency-key-blank");
        error(command(parent, "archive", 0, "x".repeat(129), null), 400, "idempotency-key-too-long");
        request().header("If-Match", "0").header("Idempotency-Key", "accepted").contentType("text/plain").body("{}")
                .post(parent.path() + "/archive").then().statusCode(415).body("status", equalTo(415));
        error(request().header("If-Match", "0").post("/v1/companies/invalid/archive"), 400, "company-id-invalid");
        error(command(parent, "archive", 1, "accepted", null), 409,
                "idempotency-key-already-used-with-different-request");
    }
}
