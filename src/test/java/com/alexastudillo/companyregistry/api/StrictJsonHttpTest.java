package com.alexastudillo.companyregistry.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class StrictJsonHttpTest {
    private RequestSpecification request() {
        return given().header("Process-Id", UUID.randomUUID().toString()).header("Tenant-Id", UUID.randomUUID().toString())
                .header("User-Id", "Actor-aB").contentType("application/json");
    }
    @Test void canonicalizesBeforeFieldValidationIncludingUppercaseExpansion() {
        request().body("{\"name\":\"  ágil  \"}").post("/v1/_verification/echo").then().statusCode(200)
                .body("status", equalTo(200)).body("data.value", equalTo("ÁGIL"));
        request().body("{\"name\":\"" + "ß".repeat(6) + "\"}").post("/v1/_verification/echo").then().statusCode(400)
                .body("status", equalTo(400)).body("code", equalTo("validation-error"))
                .body("errors", hasItem(allOf(hasEntry("field", "name"), hasEntry("code", "name-too-long"))));
        request().body("{\"name\":\"   \"}").post("/v1/_verification/echo").then().statusCode(400)
                .body("code", equalTo("validation-error"))
                .body("errors", hasItem(allOf(hasEntry("field", "name"), hasEntry("code", "name-required"))));
    }
    @Test void rejectsMalformedUnknownAndNonStringBodiesWithSharedEnvelope() {
        for (var body : new String[]{"{invalid", "{\"name\":\"OK\",\"unknown\":true}", "{\"name\":12}", "[]"}) {
            request().body(body).post("/v1/_verification/echo").then().statusCode(400)
                    .body("status", equalTo(400)).body("code", equalTo("bad-request"))
                    .body(not(containsString("JsonMappingException"))).body(not(containsString("UnrecognizedPropertyException")));
        }
        request().body("{}").post("/v1/_verification/echo").then().statusCode(400)
                .body("code", equalTo("validation-error")).body("errors.code", hasItem("name-required"));
        request().post("/v1/_verification/echo").then().statusCode(400).body("code", equalTo("validation-error"))
                .body("errors.code", hasItem("body-required"));
    }
}
