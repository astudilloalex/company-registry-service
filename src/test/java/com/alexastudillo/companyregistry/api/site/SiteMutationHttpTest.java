package com.alexastudillo.companyregistry.api.site;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.*;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class SiteMutationHttpTest {
    @Inject
    Flyway flyway;
    private final UUID tenant = UUID.randomUUID();
    private final String process = UUID.randomUUID().toString();

    private UUID parent(String status) throws Exception {
        try (var c = flyway.getConfiguration().getDataSource().getConnection();
                var s = c.createStatement();
                var r = s.executeQuery(
                        "INSERT INTO companies(tenant_id,party_id,code,operating_name,status,created_by,updated_by) VALUES('"
                                + tenant + "',uuidv7(),'" + UUID.randomUUID().toString().toUpperCase() + "','COMPANY','"
                                + status + "','actor','actor') RETURNING id")) {
            r.next();
            return r.getObject(1, UUID.class);
        }
    }

    private io.restassured.specification.RequestSpecification request() {
        return request("Actor-aB");
    }

    private io.restassured.specification.RequestSpecification request(String actor) {
        return given().header("Tenant-Id", tenant).header("User-Id", actor).header("Process-Id", process)
                .contentType("application/json");
    }

    private String site(UUID company, String type) {
        return request().header("Idempotency-Key", UUID.randomUUID()).body("{\"code\":\"SITE\",\"name\":\"SEDE\",\"siteType\":\"" + type + "\"}")
                .post("/v1/companies/" + company + "/sites").then().statusCode(201).extract().path("data.siteId");
    }

    @Test void updatesNoOpsTransitionsAndReplaysTheOriginalRepresentation() throws Exception {
        var company = parent("DRAFT");
        var path = "/v1/companies/" + company + "/sites/" + site(company, "HEADQUARTERS");
        var changed = request().header("If-Match", "0").header("Idempotency-Key", "patch")
                .body("{\"name\":\" compañía  ágil \"}").patch(path).then().statusCode(200)
                .header("Process-Id", process).body("data.version", equalTo(1), "data.siteType", equalTo("HEADQUARTERS"))
                .extract().jsonPath().getMap("data");
        assertEquals(12, changed.size());
        var noop = request().header("If-Match", "1").header("Idempotency-Key", "noop").body("{}")
                .patch(path).then().statusCode(200).body("data", equalTo(changed)).extract().jsonPath().getMap("data");
        var activated = request().header("If-Match", "1").header("Idempotency-Key", "activate").post(path + "/activate")
                .then().statusCode(200).body("data.status", equalTo("ACTIVE"), "data.version", equalTo(2), "data.effectivelyOperational", equalTo(false))
                .extract().jsonPath().getMap("data");
        request().header("If-Match", "2").post(path + "/activate").then().statusCode(409).body("code", equalTo("site-already-in-requested-state"));
        request().header("If-Match", "2").body("{}").post(path + "/deactivate").then().statusCode(200).body("data.version", equalTo(3));
        var closed = request().header("If-Match", "3").header("Idempotency-Key", "close").body("{}").post(path + "/close")
                .then().statusCode(200).body("data.status", equalTo("CLOSED"), "data.version", equalTo(4)).extract().jsonPath().getMap("data");
        request().header("If-Match", "4").post(path + "/close").then().statusCode(409).body("code", equalTo("site-closed"));
        request().header("If-Match", "3").header("Idempotency-Key", "close").post(path + "/close").then().statusCode(200).body("data", equalTo(closed));
        request("OtherActor").header("If-Match", "1").header("Idempotency-Key", "activate").body("{}").post(path + "/activate")
                .then().statusCode(200).body("data", equalTo(activated));
        request().header("If-Match", "0").header("Idempotency-Key", "patch").body("{\"name\":\"COMPAÑÍA  ÁGIL\"}")
                .patch(path).then().statusCode(200).body("data", equalTo(changed));
        request().header("If-Match", "1").header("Idempotency-Key", "noop").body("{}").patch(path).then().statusCode(200).body("data", equalTo(noop));
        request().header("If-Match", "4").header("Idempotency-Key", "noop").body("{}").patch(path).then().statusCode(409)
                .body("code", equalTo("idempotency-key-already-used-with-different-request"));
    }

    @Test void validatesBodiesAndIfMatchBeforeAcceptingOrReplaying() throws Exception {
        var company = parent("DRAFT");
        var path = "/v1/companies/" + company + "/sites/" + site(company, "BRANCH");
        for (var sample : new String[][] {
            {"", "request-body-required"}, {"null", "request-body-must-be-object"}, {"[]", "request-body-must-be-object"},
            {"{", "request-body-invalid-json"}, {"{} true", "request-body-invalid-json"},
            {"{\"code\":1,\"extra\":0}", "request-body-contains-unknown-fields"},
            {"{\"code\":1,\"code\":\"X\"}", "request-body-contains-duplicate-fields"},
            {"{\"code\":null}", "site-code-required"}, {"{\"name\":null}", "site-name-required"},
            {"{\"siteType\":null}", "site-type-null-not-allowed"}, {"{\"siteType\":\"branch\"}", "site-type-not-supported"},
            {"{\"name\":false}", "site-name-must-be-string"}, {"{\"code\":null,\"name\":false}", "site-code-required"},
            {"{\"code\":1}", "site-code-must-be-string"}, {"{\"siteType\":1}", "site-type-must-be-string"}}) {
            request().header("If-Match", "0").body(sample[0]).patch(path).then().statusCode(400)
                    .body("code", equalTo(sample[1]), "size()", equalTo(2));
        }
        for (var operation : List.of("activate", "deactivate", "close")) {
            for (var sample : new String[][] {{"null", "request-body-must-be-object"}, {"[]", "request-body-must-be-object"},
                    {"{\"reason\":\"x\"}", "request-body-contains-unknown-fields"}, {"{} {}", "request-body-invalid-json"},
                    {"{\"status\":1,\"status\":2}", "request-body-contains-unknown-fields"}}) {
                request().header("If-Match", "0").body(sample[0]).post(path + "/" + operation).then().statusCode(400)
                        .body("code", equalTo(sample[1]));
            }
        }
        request().body("{}").patch(path).then().statusCode(400).body("code", equalTo("if-match-required"));
        for (var value : List.of("01", "-1", "+1", "*", "\"0\"", "9223372036854775808")) {
            request().header("If-Match", value).body("{}").patch(path).then().statusCode(400).body("code", equalTo("if-match-invalid"));
        }
    }

    @Test void protectsLegalHeadquartersAndKeepsHistoricalOperativityAfterArchival() throws Exception {
        var company = parent("ACTIVE");
        var path = "/v1/companies/" + company + "/sites/" + site(company, "HEADQUARTERS");
        var accepted = request().header("If-Match", "0").header("Idempotency-Key", "activation").post(path + "/activate")
                .then().statusCode(200).body("data.effectivelyOperational", equalTo(true)).extract().jsonPath().getMap("data");
        for (var operation : List.of("deactivate", "close")) {
            request().header("If-Match", "1").post(path + "/" + operation).then().statusCode(409).body("code", equalTo("active-headquarters-required"));
        }
        request().header("If-Match", "1").body("{\"siteType\":\"BRANCH\"}").patch(path).then().statusCode(409).body("code", equalTo("active-headquarters-required"));
        try (var connection = flyway.getConfiguration().getDataSource().getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE companies SET status='ARCHIVED' WHERE id='" + company + "'");
        }
        request().header("If-Match", "0").body("{}").patch(path).then().statusCode(412).body("code", equalTo("aggregate-version-mismatch"));
        request().header("If-Match", "1").body("{}").patch(path).then().statusCode(409).body("code", equalTo("site-mutation-not-allowed-for-archived-company"));
        request().header("If-Match", "1").body("{}").patch("/v1/companies/" + company + "/sites/" + UUID.randomUUID()).then().statusCode(404).body("code", equalTo("site-not-found"));
        request().header("If-Match", "0").header("Idempotency-Key", "activation").body("{}").post(path + "/activate")
                .then().statusCode(200).body("data", equalTo(accepted));
        request().get(path).then().statusCode(200).body("data.effectivelyOperational", equalTo(false));
    }
}
