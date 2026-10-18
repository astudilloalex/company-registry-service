package com.alexastudillo.companyregistry.api.site;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import java.util.*;
import org.junit.jupiter.api.Test;

@QuarkusTest
@io.quarkus.test.junit.TestProfile(AddressHttpProfile.class)
class AddressHttpTest extends AddressHttpSupport {
    private static final String MINIMUM = "{\"addressType\":\" physical \",\"countryCode\":\" ec \",\"addressLine1\":\" avenida  josé \"}";

    @Test
    void creationNoOpCorrectionDeletionAndHistoricalReplayUseExactPublicShapes() throws Exception {
        var f = fixture();
        var stub = AddressReferenceResource.stub;
        stub.reset();
        country("EC", UUID.randomUUID(), "ACTIVE");
        var created = mutation(0, "original").body(MINIMUM).post(f.path()).then().statusCode(201)
                .header("Process-Id", process)
                .body("data.siteVersion", equalTo(1), "data.isPrimary", equalTo(false),
                        "data.addressLine1", equalTo("AVENIDA  JOSÉ"))
                .extract().jsonPath().getMap("data");
        assertEquals(11, created.size());
        var address = created.get("addressId").toString();
        assertEquals(7, UUID.fromString(address).version());
        var detail = f.path() + "/" + address;
        stub.reset();
        stub.reply("/api/v1/countries/by-alpha2/EC", 503, "{}");
        mutation(1, "noop").body("{}").patch(detail).then().statusCode(200).body("data", equalTo(created));
        mutation(1, null).body("{\"countryCode\":\"ec\",\"addressLine1\":\"AVENIDA  JOSÉ\"}").patch(detail)
                .then().statusCode(200).body("data", equalTo(created));
        mutation(1, "correction")
                .body("{\"houseNumber\":\"12a\",\"latitude\":-0.1234560,\"longitude\":-0.00000000}")
                .patch(detail).then().statusCode(200).body("data.siteVersion", equalTo(2),
                        "data.houseNumber", equalTo("12A"),
                        "data.latitude", instanceOf(Number.class), "data.longitude",
                        equalTo(0));
        request().get(detail).then().statusCode(200).body("data.siteVersion", equalTo(2));
        var receipt = mutation(2, "delete").delete(detail).then().statusCode(200)
                .body("data.siteVersion", equalTo(3))
                .extract().jsonPath().getMap("data");
        assertEquals(Set.of("addressId", "siteId", "siteVersion", "deletedAt", "deletedBy"), receipt.keySet());
        request().get(detail).then().statusCode(404).body("code", equalTo("address-not-found"));
        mutation(0, null).body("{}").patch(detail).then().statusCode(404).body("code",
                equalTo("address-not-found"));
        mutation(0, null).delete(detail).then().statusCode(404).body("code", equalTo("address-not-found"));
        sql("UPDATE company_sites SET status='CLOSED' WHERE id='" + f.site() + "'");
        sql("UPDATE companies SET status='ARCHIVED' WHERE id='" + f.company() + "'");
        mutation(0, "original").body(MINIMUM).post(f.path()).then().statusCode(201).body("data",
                equalTo(created));
        mutation(1, "noop").body("{}").patch(detail).then().statusCode(200).body("data", equalTo(created));
        mutation(2, "delete").delete(detail).then().statusCode(200).body("data", equalTo(receipt));
        assertTrue(stub.requests().isEmpty());
        assertEquals(3, scalar("SELECT version FROM company_sites WHERE id='" + f.site() + "'"));
        assertEquals(0, scalar("SELECT version FROM companies WHERE id='" + f.company() + "'"));
        assertEquals(3, scalar(
                "SELECT count(*) FROM company_outbox_events WHERE aggregate_id='" + f.site() + "'"));
        assertEquals(1, scalar("SELECT count(*) FROM company_site_addresses WHERE site_id='" + f.site()
                + "' AND deleted_at IS NOT NULL AND NOT is_primary"));
    }

    @Test
    void primaryReplacementReclassificationAndDeletionShareOneAuditAndRevision() throws Exception {
        var f = fixture();
        var stub = AddressReferenceResource.stub;
        stub.reset();
        var physical = seedAddress(f, "PHYSICAL", true);
        var mailing = seedAddress(f, "MAILING", true);
        var result = mutation(0, "move").body("{\"addressType\":\" mailing \"}")
                .patch(f.path() + "/" + physical)
                .then().statusCode(200)
                .body("data.isPrimary", equalTo(true), "data.siteVersion", equalTo(1))
                .extract().jsonPath().getMap("data");
        request().get(f.path() + "/" + mailing).then().statusCode(200).body("data.isPrimary", equalTo(false),
                "data.siteVersion", equalTo(1), "data.updatedAt", equalTo(result.get("updatedAt")),
                "data.updatedBy", equalTo("Actor-aB"));
        request().queryParam("addressType", "PHYSICAL").queryParam("isPrimary", "true").get(f.path()).then()
                .statusCode(200)
                .body("numberOfElements", equalTo(0), "data", empty());
        mutation(1, null).body("{\"isPrimary\":false}").patch(f.path() + "/" + physical).then().statusCode(200)
                .body("data.siteVersion", equalTo(2));
        request().queryParam("isPrimary", "true").get(f.path()).then().statusCode(200).body("data", empty());
        assertTrue(stub.requests().isEmpty());
        assertEquals(2, scalar(
                "SELECT count(*) FROM company_outbox_events WHERE aggregate_id='" + f.site() + "'"));
    }

    @Test
    void geographyRejectsMissingInactiveIncompatibleAndUnavailableReferencesWithoutAcceptance() throws Exception {
        var f = fixture();
        var stub = AddressReferenceResource.stub;
        stub.reset();
        var country = UUID.randomUUID();
        mutation(0, "key").body(MINIMUM).post(f.path()).then().statusCode(404).body("code",
                equalTo("country-reference-not-found"));
        for (var state : List.of("DRAFT", "DEPRECATED", "RETIRED")) {
            country("EC", country, state);
            mutation(0, "key").body(MINIMUM).post(f.path()).then().statusCode(409).body("code",
                    equalTo("country-must-be-active"));
        }
        country("EC", country, "ACTIVE");
        var body = MINIMUM.substring(0, MINIMUM.length() - 1) + ",\"administrativeDivisionCode\":\"EC-P\"}";
        var path = "/api/v1/countries/" + country + "/administrative-divisions/by-canonical-code/EC-P";
        mutation(0, "key").body(body).post(f.path()).then().statusCode(404).body("code",
                equalTo("administrative-division-reference-not-found"));
        for (var state : List.of("DRAFT", "DEPRECATED", "RETIRED")) {
            stub.reply(path, 200, division(country, "EC-P", state));
            mutation(0, "key").body(body).post(f.path()).then().statusCode(409).body("code",
                    equalTo("administrative-division-must-be-active"));
        }
        stub.reply(path, 200, division(UUID.randomUUID(), "EC-P", "ACTIVE"));
        mutation(0, "key").body(body).post(f.path()).then().statusCode(502).body("code",
                equalTo("dependency-response-invalid"));
        stub.reply(path, 503, "{}");
        mutation(0, "key").body(body).post(f.path()).then().statusCode(503).body("code",
                equalTo("dependency-unavailable"));
        assertEquals(0, scalar("SELECT count(*) FROM company_site_addresses WHERE site_id='" + f.site() + "'"));
        assertEquals(0, scalar(
                "SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant + "'"));
        stub.reply(path, 200, division(country, "EC-P", "ACTIVE"));
        var address = mutation(0, "key").body(body).post(f.path()).then().statusCode(201).extract()
                .path("data.addressId");
        var country2 = UUID.randomUUID();
        country("CO", country2, "ACTIVE");
        mutation(1, null).body("{\"countryCode\":\"CO\"}").patch(f.path() + "/" + address).then()
                .statusCode(404)
                .body("code", equalTo("administrative-division-reference-not-found"));
        mutation(1, null).body("{\"countryCode\":\"CO\",\"administrativeDivisionCode\":null}")
                .patch(f.path() + "/" + address)
                .then().statusCode(200)
                .body("data.countryCode", equalTo("CO"), "data.siteVersion", equalTo(2));
        stub.reset();
        mutation(2, null).body("{\"addressLine1\":\"CORRECCIÓN\",\"administrativeDivisionCode\":null}")
                .patch(f.path() + "/" + address)
                .then().statusCode(200).body("data.siteVersion", equalTo(3));
        assertTrue(stub.requests().isEmpty());
    }

    @Test
    void absenceRevisionStateAndIdempotencyRespectPrecedenceAndScope() throws Exception {
        var f = fixture();
        var address = seedAddress(f, "PHYSICAL", false);
        var path = f.path() + "/" + address;
        sql("UPDATE companies SET status='ARCHIVED' WHERE id='" + f.company() + "'");
        sql("UPDATE company_sites SET status='CLOSED',version=1 WHERE id='" + f.site() + "'");
        mutation(0, null).body("{}").patch(path).then().statusCode(412).body("code",
                equalTo("aggregate-version-mismatch"));
        mutation(1, null).body("{}").patch(path).then().statusCode(409).body("code",
                equalTo("site-mutation-not-allowed-for-archived-company"));
        mutation(0, null).body("{}").patch(f.path() + "/" + UUID.randomUUID()).then().statusCode(404)
                .body("code", equalTo("address-not-found"));
        var other = fixture();
        request().get(other.path() + "/" + address).then().statusCode(404).body("code",
                equalTo("address-not-found"));
        request().get("/v1/companies/" + other.company() + "/sites/" + f.site() + "/addresses/" + address)
                .then().statusCode(404).body("code", equalTo("site-not-found"));
        io.restassured.RestAssured.given().header("Tenant-Id", UUID.randomUUID()).header("User-Id", "Actor-aB")
                .header("Process-Id", process).get(path).then().statusCode(404)
                .body("code", equalTo("company-not-found"));
        request().get(path).then().statusCode(200).body("data.siteVersion", equalTo(1));
        request().get(f.path()).then().statusCode(200).body("numberOfElements", equalTo(1));
    }

    @Test
    void filteredPaginationUsesCreationIdOrderAndAuthenticatedScope() throws Exception {
        var f = fixture();
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 3; i++)
            ids.add(seedAddress(f, "PHYSICAL", false));
        var first = request().queryParam("limit", 1).get(f.path()).then().statusCode(200)
                .body("numberOfElements", equalTo(1))
                .extract().jsonPath();
        assertEquals(ids.getLast().toString(), first.getString("data[0].addressId"));
        var cursor = first.getString("nextCursor");
        request().queryParam("limit", 200).queryParam("cursor", cursor).get(f.path()).then().statusCode(200)
                .body("numberOfElements", equalTo(2), "data[0].addressId",
                        equalTo(ids.get(1).toString()),
                        "data[1].addressId", equalTo(ids.getFirst().toString()), "nextCursor",
                        nullValue(), "totalElements", nullValue());
        request().queryParam("cursor", cursor).queryParam("isPrimary", false).get(f.path()).then()
                .statusCode(400)
                .body("code", equalTo("address-cursor-invalid-or-incompatible"));
        var other = fixture();
        request().queryParam("cursor", cursor).get(other.path()).then().statusCode(400).body("code",
                equalTo("address-cursor-invalid-or-incompatible"));
        for (var sample : new String[][] { { "limit", "0", "address-page-limit-must-be-between-1-and-200" },
                { "limit", "9999999999999999999999", "address-page-limit-must-be-between-1-and-200" },
                { "limit", "1.0", "address-page-limit-must-be-an-integer" },
                { "isPrimary", "False", "address-is-primary-filter-invalid" },
                { "addressType", "physical", "address-type-not-supported" },
                { "unknown", "x", "query-parameter-not-supported" } }) {
            request().queryParam(sample[0], sample[1]).get(f.path()).then().statusCode(400).body("code",
                    equalTo(sample[2]));
        }
    }

    @Test
    void httpBoundaryKeepsClosedJsonExactTypesMutationHeadersAndSanitizedCodes() throws Exception {
        var f = fixture();
        var address = seedAddress(f, "PHYSICAL", false);
        var detail = f.path() + "/" + address;
        for (var sample : new String[][] {
                { "{\"countryCode\":null,\"addressLine1\":false}", "address-country-code-required" },
                { "{\"addressId\":\"x\"}", "request-body-contains-unknown-fields" },
                { "{\"postalCode\":1,\"postalCode\":null}", "request-body-contains-duplicate-fields" },
                { "{} true", "request-body-invalid-json" }, { "[]", "request-body-must-be-object" },
                { "{\"latitude\":1}", "address-coordinates-must-be-provided-together" },
                { "{\"latitude\":90.0000001,\"longitude\":0}", "address-latitude-out-of-range" },
                { "{\"latitude\":0.1234567,\"longitude\":0}", "address-latitude-precision-exceeded" },
                { "{\"latitude\":\"1\",\"longitude\":2}", "address-latitude-must-be-number" },
                { "{\"isPrimary\":null}", "address-is-primary-must-be-boolean" } }) {
            mutation(0, "invalid").body(sample[0]).patch(detail).then().statusCode(400)
                    .header("Process-Id", process)
                    .body("code", equalTo(sample[1]), "size()", equalTo(2));
        }
        request().body("{}").patch(detail).then().statusCode(400).body("code", equalTo("if-match-required"));
        mutation(0, null).body(MINIMUM).post(f.path()).then().statusCode(400).body("code",
                equalTo("idempotency-key-required"));
        mutation(0, "invalid").contentType("text/plain").body("{}").patch(detail).then().statusCode(415)
                .body("code", equalTo("unsupported-media-type"));
        request().get(f.path() + "/bad-id").then().statusCode(400).body("code", equalTo("address-id-invalid"));
        request().queryParam("limit", 1, 2).get(f.path()).then().statusCode(400).body("code",
                equalTo("query-parameter-repeated"));
        assertEquals(0, scalar("SELECT version FROM company_sites WHERE id='" + f.site() + "'"));
        assertEquals(0, scalar(
                "SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant + "'"));
    }

    @Test
    void removingOnlyDivisionAndLocalCorrectionsRemainAvailableWithoutProvider() throws Exception {
        var f = fixture();
        var address = seedAddress(f, "PHYSICAL", false);
        var detail = f.path() + "/" + address;
        sql("UPDATE company_site_addresses SET administrative_division_code='EC-P' WHERE id='" + address + "'");
        var stub = AddressReferenceResource.stub;
        stub.reset();
        stub.reply("/api/v1/countries/by-alpha2/EC", 503, "{}");
        mutation(0, null).body("{\"addressLine2\":\"PISO 2\"}").patch(detail).then().statusCode(200)
                .body("data.siteVersion", equalTo(1));
        mutation(1, null).body("{\"administrativeDivisionCode\":null}").patch(detail).then().statusCode(200)
                .body("data.siteVersion", equalTo(2));
        assertTrue(stub.requests().isEmpty());
    }

    @Test
    void corruptedHistoricalIdentityFailsClosedWithoutRepairOrCurrentStateFallback() throws Exception {
        var f = fixture();
        var address = seedAddress(f, "PHYSICAL", false);
        var detail = f.path() + "/" + address;
        var body = "{\"houseNumber\":\"12A\"}";
        mutation(0, "history").body(body).patch(detail).then().statusCode(200).body("data.siteVersion",
                equalTo(1));
        sql("UPDATE company_idempotency_records SET result_snapshot=jsonb_set(result_snapshot,'{result,address,id}',to_jsonb(uuidv7()::text)) WHERE tenant_id='"
                + tenant + "'");
        mutation(0, "history").body(body).patch(detail).then().statusCode(500).header("Process-Id", process)
                .body("code", equalTo("server-error"), "size()", equalTo(2));
        assertEquals(1, scalar("SELECT version FROM company_sites WHERE id='" + f.site() + "'"));
        assertEquals(1, scalar(
                "SELECT count(*) FROM company_outbox_events WHERE aggregate_id='" + f.site() + "'"));
    }
}
