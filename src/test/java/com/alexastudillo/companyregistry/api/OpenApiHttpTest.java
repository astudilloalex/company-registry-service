package com.alexastudillo.companyregistry.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import com.alexastudillo.companyregistry.contract.OpenApiAssertions;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class OpenApiHttpTest {
    @Test void packagedAndPublishedContractMatchVersionedSourceWithoutBusinessHeaders() throws Exception {
        var source = Files.readString(Path.of("docs/contracts/company-registry.openapi.yaml"));
        try (var resource = getClass().getClassLoader().getResourceAsStream("META-INF/openapi.yaml")) {
            assertNotNull(resource); assertEquals(source, new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        }
        var published = given().accept("application/json").get("/q/openapi").then().statusCode(200).extract().asString();
        OpenApiAssertions.publishedMatchesSource(source, published);
        given().get("/q/health/ready").then().statusCode(200);
    }
}
