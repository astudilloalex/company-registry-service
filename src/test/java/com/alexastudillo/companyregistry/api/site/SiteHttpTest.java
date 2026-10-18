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
class SiteHttpTest {
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

    @Test
    void registersReplaysAndReadsLocalSiteWithoutMutationHeadersOrProviders() throws Exception {
        var company = parent("ACTIVE");
        var path = "/v1/companies/" + company + "/sites";
        var data = request().header("Idempotency-Key", "one").body("{\"code\":\" ß \",\"name\":\" compañía  ágil \"}")
                .post(path).then().statusCode(201).header("Process-Id", process).body("code", equalTo("successful"))
                .body("data.code", equalTo("SS"), "data.name", equalTo("COMPAÑÍA  ÁGIL"), "data.siteType",
                        equalTo("BRANCH"),
                        "data.status", equalTo("DRAFT"), "data.version", equalTo(0), "data.effectivelyOperational",
                        equalTo(false))
                .extract().jsonPath().getMap("data");
        assertEquals(12, data.size());
        var site = data.get("siteId");
        request().get(path + "/" + site).then().statusCode(200).body("data", equalTo(data));
        request().get(path).then().statusCode(200).body("numberOfElements", equalTo(1), "data[0]", equalTo(data))
                .body("totalElements", nullValue(), "totalPages", nullValue(), "prevCursor", nullValue(), "nextCursor",
                        nullValue());
        request("Another-actor").header("Idempotency-Key", "one")
                .body("{\"siteType\":\"BRANCH\",\"name\":\"COMPAÑÍA  ÁGIL\",\"code\":\"SS\"}")
                .post(path).then().statusCode(201).body("data", equalTo(data));
        request().header("Idempotency-Key", "two").body("{\"code\":\"ss\",\"name\":\"x\"}").post(path)
                .then().statusCode(409).body("code", equalTo("site-code-already-exists")).body("size()", equalTo(2));
        request().queryParam("code", " ss ").queryParam("siteType", "BRANCH").queryParam("status", "DRAFT").get(path)
                .then().statusCode(200).body("numberOfElements", equalTo(1));
        request().get(path + "?status=CLOSED").then().statusCode(200).body("data", empty());
    }

    @Test
    void reportsClosedBodyErrorsWithFullDocumentPrecedence() throws Exception {
        var path = "/v1/companies/" + parent("DRAFT") + "/sites";
        for (var sample : new String[][] {
                { "", "request-body-required" }, { "{", "request-body-invalid-json" },
                { "null", "request-body-must-be-object" }, { "[]", "request-body-must-be-object" },
                { "{} true", "request-body-invalid-json" },
                { "{\"code\":1,\"name\":false,\"extra\":1}", "request-body-contains-unknown-fields" },
                { "{\"code\":1,\"code\":\"x\"}", "request-body-contains-duplicate-fields" },
                { "{\"name\":false}", "site-code-required" },
                { "{\"name\":false,\"code\":1}", "site-code-must-be-string" },
                { "{\"code\":\"x\",\"name\":false}", "site-name-must-be-string" },
                { "{\"code\":\"x\",\"name\":\"y\",\"siteType\":null}", "site-type-null-not-allowed" },
                { "{\"code\":\"x\",\"name\":\"y\",\"siteType\":1}", "site-type-must-be-string" },
                { "{\"code\":\"x\",\"name\":\"y\",\"siteType\":\"branch\"}", "site-type-not-supported" },
                { "{\"code\":\"" + "ß".repeat(33) + "\",\"name\":\"x\"}", "site-code-too-long" },
                { "{\"code\":\"x\",\"name\":null}", "site-name-required" },
                { "{\"code\":\"x\",\"name\":\"" + "ß".repeat(101) + "\"}", "site-name-too-long" } }) {
            request().header("Idempotency-Key", UUID.randomUUID()).body(sample[0]).post(path).then().statusCode(400)
                    .body("code", equalTo(sample[1]), "size()", equalTo(2));
        }
    }

    @Test
    void distinguishesParentSiteAndQueryFailures() throws Exception {
        var company = parent("DRAFT");
        var path = "/v1/companies/" + company + "/sites";
        request().get(path).then().statusCode(200).body("data", empty(), "numberOfElements", equalTo(0));
        request().get(path + "/" + UUID.randomUUID()).then().statusCode(404).body("code", equalTo("site-not-found"));
        request().get("/v1/companies/" + UUID.randomUUID() + "/sites").then().statusCode(404).body("code",
                equalTo("company-not-found"));
        for (var query : new String[][] { { "foo=1", "query-parameter-not-supported" },
                { "code=x&code=y", "query-parameter-repeated" },
                { "limit=0", "site-page-limit-must-be-between-1-and-200" },
                { "limit=99999999999999999999", "site-page-limit-must-be-between-1-and-200" },
                { "limit=1.5", "site-page-limit-must-be-an-integer" },
                { "cursor=bad", "site-cursor-invalid-or-incompatible" } }) {
            request().get(path + "?" + query[0]).then().statusCode(400).body("code", equalTo(query[1]), "size()",
                    equalTo(2));
        }
    }

    @Test
    void historicalPageTraversesEqualTimestampsAndUsesTheJoinedParentState() throws Exception {
        var company = parent("ARCHIVED");
        var path = "/v1/companies/" + company + "/sites";
        try (var c = flyway.getConfiguration().getDataSource().getConnection(); var s = c.createStatement()) {
            for (var status : List.of("DRAFT", "ACTIVE", "INACTIVE", "CLOSED")) {
                s.execute(
                        "INSERT INTO company_sites(tenant_id,company_id,code,name,status,created_at,updated_at,created_by,updated_by) VALUES('"
                                + tenant + "','" + company + "','" + status + "','SITE','" + status
                                + "','2026-10-17T12:00:00.123456Z','2026-10-17T12:00:00.123456Z','Creator','Creator')");
            }
        }
        var seen = new HashSet<String>();
        String cursor = null;
        do {
            var q = request().queryParam("limit", 1);
            if (cursor != null)
                q.queryParam("cursor", cursor);
            var page = q.get(path).then().statusCode(200).body("numberOfElements", equalTo(1),
                    "data[0].effectivelyOperational", equalTo(false)).extract().jsonPath();
            assertTrue(seen.add(page.getString("data[0].siteId")));
            cursor = page.getString("nextCursor");
        } while (cursor != null);
        assertEquals(4, seen.size());
        request().queryParam("status", "ACTIVE").get(path).then().statusCode(200)
                .body("numberOfElements", equalTo(1), "data[0].status", equalTo("ACTIVE"),
                        "data[0].effectivelyOperational", equalTo(false));
        var active = parent("ACTIVE");
        try (var c = flyway.getConfiguration().getDataSource().getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO company_sites(tenant_id,company_id,code,name,status,created_by,updated_by) VALUES('"
                    + tenant + "','" + active + "','ACTIVE','SITE','ACTIVE','Creator','Creator')");
        }
        request().get("/v1/companies/" + active + "/sites").then().statusCode(200)
                .body("data[0].effectivelyOperational", equalTo(true));
    }

    @Test
    void headersPathsAndAllQueryDiagnosticsUseSpecificRootCodes() throws Exception {
        var company = parent("DRAFT");
        var path = "/v1/companies/" + company + "/sites";
        var body = "{\"code\":\"x\",\"name\":\"y\"}";
        request().body(body).post(path).then().statusCode(400).body("code", equalTo("idempotency-key-required"),
                "size()", equalTo(2));
        request().header("Idempotency-Key", "one", "two").body(body).post(path).then().statusCode(400).body("code",
                equalTo("idempotency-key-duplicated"));
        request().header("Idempotency-Key", " ").body(body).post(path).then().statusCode(400).body("code",
                equalTo("idempotency-key-blank"));
        request().header("Idempotency-Key", "x".repeat(129)).body(body).post(path).then().statusCode(400).body("code",
                equalTo("idempotency-key-too-long"));
        request().get("/v1/companies/invalid/sites").then().statusCode(400).body("code", equalTo("company-id-invalid"));
        request().get(path + "/invalid").then().statusCode(400).body("code", equalTo("site-id-invalid"));
        for (var pair : new String[][] { { "code", " " }, { "code", "x".repeat(65) }, { "siteType", "branch" },
                { "status", "ARCHIVED" }, { "cursor", " " } }) {
            var code = pair[0].equals("code") ? (pair[1].length() == 1 ? "site-code-required" : "site-code-too-long")
                    : pair[0].equals("siteType") ? "site-type-not-supported"
                            : pair[0].equals("status") ? "site-status-not-supported"
                                    : "site-cursor-invalid-or-incompatible";
            request().queryParam(pair[0], pair[1]).get(path).then().statusCode(400).body("code", equalTo(code),
                    "size()", equalTo(2));
        }
        var archived = "/v1/companies/" + parent("ARCHIVED") + "/sites";
        request().header("Idempotency-Key", "archived").body(body).post(archived).then().statusCode(409)
                .body("code", equalTo("site-registration-not-allowed-for-archived-company"), "size()", equalTo(2));
    }

    @Test
    void closedHeadquartersKeepsItsCodeButAllowsReplacementWithAFreeCodeAndOtherCompaniesReuseCodes() throws Exception {
        var company = parent("DRAFT");
        var path = "/v1/companies/" + company + "/sites";
        var first = request().header("Idempotency-Key", "hq")
                .body(Map.of("code", "HQ", "name", "Legal", "siteType", "HEADQUARTERS"))
                .post(path).then().statusCode(201).extract().jsonPath().getString("data.siteId");
        for (var state : List.of("DRAFT", "ACTIVE", "INACTIVE")) {
            try (var c = flyway.getConfiguration().getDataSource().getConnection(); var s = c.createStatement()) {
                s.execute("UPDATE company_sites SET status='" + state + "' WHERE id='" + first + "'");
            }
            request().header("Idempotency-Key", state)
                    .body(Map.of("code", "NEW", "name", "Legal", "siteType", "HEADQUARTERS"))
                    .post(path).then().statusCode(409).body("code", equalTo("headquarters-already-exists"));
        }
        try (var c = flyway.getConfiguration().getDataSource().getConnection(); var s = c.createStatement()) {
            s.execute("UPDATE company_sites SET status='CLOSED' WHERE id='" + first + "'");
        }
        request().header("Idempotency-Key", "occupied")
                .body(Map.of("code", "hq", "name", "Legal", "siteType", "HEADQUARTERS"))
                .post(path).then().statusCode(409).body("code", equalTo("site-code-already-exists"));
        request().header("Idempotency-Key", "replacement")
                .body(Map.of("code", "NEW", "name", "Legal", "siteType", "HEADQUARTERS"))
                .post(path).then().statusCode(201);
        var other = "/v1/companies/" + parent("DRAFT") + "/sites";
        request().header("Idempotency-Key", "other").body(Map.of("code", "hq", "name", "Other"))
                .post(other).then().statusCode(201);
        request().get(other + "/" + first).then().statusCode(404).body("code", equalTo("site-not-found"));
        given().header("Tenant-Id", UUID.randomUUID()).header("User-Id", "Actor")
                .header("Process-Id", UUID.randomUUID())
                .get(path + "/" + first).then().statusCode(404).body("code", equalTo("company-not-found"));
    }

}
