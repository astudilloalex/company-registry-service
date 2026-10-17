package com.alexastudillo.companyregistry.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Exercises framework failures through the shared error boundary, including the business root path. */
@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class FrameworkHttpTest {
    private RequestSpecification request() {
        return given().header("Tenant-Id", UUID.randomUUID().toString()).header("Process-Id", UUID.randomUUID().toString())
                .header("User-Id", "Actor-aB");
    }
    @Test void frameworkFailuresHaveMatchingStatusAndSanitizedSharedBodies() {
        assertError(request().get("/v1/missing"), 404, "not-found");
        assertError(request().get("/v1"), 404, "not-found");
        assertError(request().put("/v1/_verification/success"), 405, "method-not-allowed");
        assertError(request().contentType("text/plain").body("private-input").post("/v1/_verification/echo"), 415, "unsupported-media-type");
        assertError(request().get("/v1/_verification/unexpected-failure"), 500, "server-error");
    }
    private static void assertError(Response response, int status, String code) {
        response.then().statusCode(status).contentType("application/json")
                .body("status", equalTo(status)).body("code", equalTo(code)).body("data", nullValue())
                .body(not(containsString("Exception"))).body(not(containsString("stackTrace")))
                .body(not(containsString("SELECT"))).body(not(containsString("private-")))
                .body(not(containsString("tenantId"))).body(not(containsString("operatingName")));
    }
}
