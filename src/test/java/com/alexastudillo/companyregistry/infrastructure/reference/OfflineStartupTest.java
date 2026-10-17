package com.alexastudillo.companyregistry.infrastructure.reference;

import static io.restassured.RestAssured.given;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(OfflineStartupProfile.class) @QuarkusTestResource(PostgresResource.class)
class OfflineStartupTest {
    @Test void startupAndManagementDoNotContactUnavailableProviders() {
        given().get("/q/health/ready").then().statusCode(200);
        given().get("/q/openapi").then().statusCode(200);
    }
}
