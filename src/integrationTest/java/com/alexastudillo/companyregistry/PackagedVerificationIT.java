package com.alexastudillo.companyregistry;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import com.alexastudillo.companyregistry.contract.ReferenceStubServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.specification.RequestSpecification;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Real verification-process contracts; no injected beans or in-process mocks. */
@QuarkusIntegrationTest @QuarkusTestResource(ProcessEnvironmentResource.class)
@EnabledIfSystemProperty(named = "verification.artifact", matches = "true")
class PackagedVerificationIT {
    private RequestSpecification request() {
        return given().header("Tenant-Id", UUID.randomUUID().toString()).header("User-Id", "Actor-aB")
                .header("Process-Id", UUID.randomUUID().toString());
    }
    @Test void sixMappingsCommitAndRollbackWithinTheExternalProcess() {
        for (boolean rollback : new boolean[]{false, true}) {
            var data = request().post("/v1/_verification/persistence/" + rollback).then().statusCode(200)
                    .body("status", equalTo(200)).body("code", equalTo("successful")).body("data.rolledBack", equalTo(rollback))
                    .extract().jsonPath();
            for (var key : List.of("companyId", "siteId", "eventId")) {
                var id = UUID.fromString(data.getString("data." + key)); assertEquals(7, id.version()); assertEquals(2, id.variant());
            }
        }
    }
    @Test void strictInputHeadersAndSharedFrameworkErrors() {
        var process = UUID.randomUUID().toString();
        given().header("Process-Id", process).get("/v1/_verification/success").then().statusCode(400)
                .header("Process-Id", equalTo(process)).body("status", equalTo(400)).body("code", equalTo("tenant-id-invalid"));
        for (var header : List.of("Process-Id", "Tenant-Id", "User-Id")) {
            var headers = new HashMap<>(Map.of("Process-Id", process, "Tenant-Id", UUID.randomUUID().toString(), "User-Id", "Actor-aB"));
            var original = headers.remove(header);
            given().headers(headers).get("/v1/_verification/success").then().statusCode(400).body("status", equalTo(400));
            headers.put(header, original);
            given().headers(headers).header(header, original).get("/v1/_verification/success").then().statusCode(400).body("status", equalTo(400));
            headers.put(header, header.equals("User-Id") ? "a".repeat(129) : "1-1-1-1-1");
            given().headers(headers).get("/v1/_verification/success").then().statusCode(400).body("status", equalTo(400));
        }
        request().get("/v1/_verification/known-failure").then().statusCode(400).body("status", equalTo(400)).body("code", equalTo("reference-invalid"));
        for (var body : List.of("{invalid", "{\"name\":\"OK\",\"unknown\":true}", "{\"name\":12}", "[]")) {
            request().contentType("application/json").body(body).post("/v1/_verification/echo").then().statusCode(400)
                    .body("status", equalTo(400)).body("code", equalTo("bad-request"));
        }
        request().contentType("application/json").body("{\"name\":\"  ágil  \"}").post("/v1/_verification/echo").then()
                .statusCode(200).body("status", equalTo(200)).body("data.value", equalTo("ÁGIL"));
        request().contentType("application/json").body("{\"name\":\"ßßßßßß\"}").post("/v1/_verification/echo").then()
                .statusCode(400).body("code", equalTo("validation-error")).body("errors.code", hasItem("name-too-long"));
        request().get("/v1/missing").then().statusCode(404).body("status", equalTo(404));
        request().put("/v1/_verification/success").then().statusCode(405).body("status", equalTo(405));
        request().contentType("text/plain").body("private").post("/v1/_verification/echo").then().statusCode(415).body("status", equalTo(415));
        var failure = request().get("/v1/_verification/unexpected-failure").then().statusCode(500)
                .body("status", equalTo(500)).body("code", equalTo("server-error")).extract().asString();
        for (var secret : List.of("Exception", "stackTrace", "SELECT", "private-provider")) assertFalse(failure.contains(secret));
    }
    @Test void asynchronousContextRemainsIsolatedBetweenConcurrentRequests() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) {
                var process = UUID.randomUUID().toString();
                tasks.add(executor.submit(() -> given().header("Tenant-Id", UUID.randomUUID().toString()).header("User-Id", "Actor-aB")
                        .header("Process-Id", process).get("/v1/_verification/context").then().statusCode(200)
                        .header("Process-Id", equalTo(process)).body("data.value", equalTo(process))));
            }
            for (var task : tasks) task.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    @Test void realClientsReachExternalUrlStubsAndClassifyFailures() {
        var stub = ProcessEnvironmentResource.stub; stub.reset();
        var party = UUID.randomUUID(); var country = UUID.randomUUID(); var division = UUID.randomUUID();
        var partyPath = "/v1/parties/" + party; var countryPath = "/api/v1/countries/by-alpha2/EC";
        var divisionPath = "/api/v1/countries/" + country + "/administrative-divisions/by-canonical-code/EC-P";
        stub.reply(partyPath, 200, "{\"status\":200,\"code\":\"successful\",\"data\":{\"partyId\":\"" + party + "\",\"type\":\"LEGAL_ENTITY\",\"recordStatus\":\"ACTIVE\",\"private\":\"ignored\"}}");
        stub.reply(countryPath, 200, "{\"status\":200,\"code\":\"successful\",\"data\":{\"id\":\"" + country + "\",\"alpha2Code\":\"EC\",\"status\":\"ACTIVE\"}}");
        stub.reply(divisionPath, 200, "{\"status\":200,\"code\":\"successful\",\"data\":{\"id\":\"" + division + "\",\"countryId\":\"" + country + "\",\"canonicalCode\":\"EC-P\",\"status\":\"ACTIVE\"}}");
        var process = UUID.randomUUID().toString(); var tenant = UUID.randomUUID().toString();
        given().header("Tenant-Id", tenant).header("User-Id", "Actor-aB").header("Process-Id", process)
                .get("/v1/_verification/references/party/" + party).then().statusCode(200).body("data.id", equalTo(party.toString())).body("data.active", equalTo(true));
        request().get("/v1/_verification/references/country/EC").then().statusCode(200).body("data.id", equalTo(country.toString()));
        request().get("/v1/_verification/references/division/EC/EC-P").then().statusCode(200)
                .body("data.id", equalTo(division.toString())).body("data.countryId", equalTo(country.toString()));
        assertEquals(process, stub.requests().getFirst().header("Process-Id")); assertEquals(tenant, stub.requests().getFirst().header("Tenant-Id"));
        for (var captured : stub.requests()) if (captured.path().startsWith("/api/")) {
            assertNull(captured.header("Tenant-Id")); assertNull(captured.header("company-id"));
        }
        for (var route : Map.of(partyPath, "/party/" + party, countryPath, "/country/EC").entrySet()) {
            stub.reply(route.getKey(), 404, "{\"status\":404,\"code\":\"not-found\"}");
            request().get("/v1/_verification/references" + route.getValue()).then().statusCode(404).body("code", equalTo("reference-not-found"));
            stub.reply(route.getKey(), 200, "{invalid-private");
            request().get("/v1/_verification/references" + route.getValue()).then().statusCode(502).body("code", equalTo("dependency-response-invalid"));
            stub.reply(route.getKey(), 200, "{\"status\":200,\"code\":\"successful\",\"data\":{}}");
            request().get("/v1/_verification/references" + route.getValue()).then().statusCode(502);
            stub.reply(route.getKey(), 503, "{\"status\":503,\"code\":\"unavailable\"}");
            request().get("/v1/_verification/references" + route.getValue()).then().statusCode(503).body("code", equalTo("dependency-unavailable"));
            stub.reply(route.getKey(), new ReferenceStubServer.Reply(200, "{}", 1200));
            request().get("/v1/_verification/references" + route.getValue()).then().statusCode(503).body("code", equalTo("dependency-unavailable"));
        }
        stub.reply(countryPath, 200, "{\"status\":200,\"code\":\"successful\",\"data\":{\"id\":\"" + country + "\",\"alpha2Code\":\"EC\",\"status\":\"ACTIVE\"}}");
        stub.reply(divisionPath, 404, "{\"status\":404,\"code\":\"administrative-division-not-found\"}");
        request().get("/v1/_verification/references/division/EC/EC-P").then().statusCode(404).body("code", equalTo("reference-not-found"));
        for (var invalid : List.of("{invalid", "{\"status\":200,\"code\":\"successful\",\"data\":{\"id\":\"" + division
                + "\",\"countryId\":\"" + UUID.randomUUID() + "\",\"canonicalCode\":\"EC-P\",\"status\":\"ACTIVE\"}}")) {
            stub.reply(divisionPath, 200, invalid);
            request().get("/v1/_verification/references/division/EC/EC-P").then().statusCode(502).body("code", equalTo("dependency-response-invalid"));
        }
        stub.reply(divisionPath, 503, "{\"status\":503,\"code\":\"unavailable\"}");
        request().get("/v1/_verification/references/division/EC/EC-P").then().statusCode(503).body("code", equalTo("dependency-unavailable"));
        stub.reply(divisionPath, new ReferenceStubServer.Reply(200, "{}", 1200));
        request().get("/v1/_verification/references/division/EC/EC-P").then().statusCode(503).body("code", equalTo("dependency-unavailable"));
    }
}
