package com.alexastudillo.companyregistry.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class FoundationHttpTest {
    @Test void successMapsApiDtoBeforeSharedEnvelope() {
        request().get("/v1/_verification/success").then().statusCode(200)
                .body("status", equalTo(200)).body("code", equalTo("successful"))
                .body("data.value", equalTo("ready")).body("data.tenantId", nullValue());
    }
    @Test void neutralFailuresUseServiceCodeAndUnexpectedFailuresAreSanitized() {
        request().get("/v1/_verification/known-failure").then().statusCode(400)
                .body("status", equalTo(400)).body("code", equalTo("reference-invalid"))
                .body(not(containsString("private-provider-payload")));
        request().get("/v1/_verification/unexpected-failure").then().statusCode(500)
                .body("status", equalTo(500)).body("code", equalTo("server-error"))
                .body(not(containsString("SELECT"))).body(not(containsString("IllegalStateException")))
                .body(not(containsString("private-provider-payload")));
    }
    private io.restassured.specification.RequestSpecification request() {
        return given().header("Process-Id", java.util.UUID.randomUUID().toString())
                .header("Tenant-Id", java.util.UUID.randomUUID().toString()).header("User-Id", "Actor-aB");
    }
}
