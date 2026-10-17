package com.alexastudillo.companyregistry;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import com.alexastudillo.companyregistry.contract.OpenApiAssertions;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** External-process checks: no CDI injection or src/test fixture outputs are present in this suite. */
@QuarkusIntegrationTest @QuarkusTestResource(ProcessEnvironmentResource.class)
class PackagedFoundationIT {
    @Test void healthAndEmbeddedContractWorkWithoutBusinessContext() throws Exception {
        var health = given().get("/q/health/ready").then().statusCode(200).extract().asString();
        assertFalse(health.toLowerCase(java.util.Locale.ROOT).contains("rabbitmq"));
        var published = given().accept("application/json").get("/q/openapi").then().statusCode(200).extract().asString();
        OpenApiAssertions.publishedMatchesSource(Files.readString(Path.of("docs/contracts/company-registry.openapi.yaml")), published);
    }
    @Test void productionExcludesVerificationRoutesWhileExplicitVariantIncludesThem() {
        var response = given().header("Tenant-Id", UUID.randomUUID().toString()).header("User-Id", "Actor-aB")
                .header("Process-Id", UUID.randomUUID().toString()).get("/v1/_verification/success");
        if (Boolean.getBoolean("verification.artifact")) {
            response.then().statusCode(200).body("status", equalTo(200)).body("code", equalTo("successful")).body("data.value", equalTo("ready"));
        } else {
            response.then().statusCode(404).body("status", equalTo(404)).body("code", equalTo("not-found"));
        }
    }
}
