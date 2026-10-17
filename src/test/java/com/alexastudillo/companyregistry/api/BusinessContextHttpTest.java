package com.alexastudillo.companyregistry.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class BusinessContextHttpTest {
    private static final String PATH = "/v1/_verification/context";
    private Map<String, String> headers() {
        return new LinkedHashMap<>(Map.of("Process-Id", UUID.randomUUID().toString(),
                "Tenant-Id", UUID.randomUUID().toString(), "User-Id", "Actor-aB"));
    }
    @Test void rejectsUppercaseContextBeforeGetPostAndReplay() {
        for (var header : List.of("Tenant-Id", "Process-Id")) {
            for (var path : List.of("/v1/companies", "/v1/companies/0199e000-0000-7000-8000-000000000001")) {
                var h = headers();
                h.put(header, "ABCDEF01-2345-6789-ABCD-EF0123456789");
                var response = given().headers(h).get(path).then().statusCode(400)
                        .body("code", equalTo(header.toLowerCase(Locale.ROOT) + "-invalid"));
                response.header("Process-Id", header.equals("Process-Id") ? nullValue() : equalTo(h.get("Process-Id")));
            }
            var h = headers();
            h.put(header, "ABCDEF01-2345-6789-ABCD-EF0123456789");
            // Reusing the key must never bypass context validation, even before routes exist.
            for (int attempt = 0; attempt < 2; attempt++) {
                given().headers(h).header("Idempotency-Key", "context-replay")
                        .contentType("application/json").body("{}")
                        .post("/v1/companies").then().statusCode(400)
                        .body("code", equalTo(header.toLowerCase(Locale.ROOT) + "-invalid"))
                        .header("Process-Id", header.equals("Process-Id") ? nullValue() : equalTo(h.get("Process-Id")));
            }
        }
    }
    @Test void rejectsMissingDuplicateAndInvalidHeadersAndKeepsValidatedProcess() {
        for (var header : List.of("Process-Id", "Tenant-Id", "User-Id")) {
            var h = headers(); var process = h.get("Process-Id"); h.remove(header);
            var response = given().headers(h).get(PATH).then().statusCode(400)
                    .body("status", equalTo(400)).body("code", equalTo(header.toLowerCase(Locale.ROOT) + "-invalid"));
            if (!header.equals("Process-Id")) response.header("Process-Id", equalTo(process));
            h = headers();
            given().headers(h).header(header, h.get(header)).get(PATH).then().statusCode(400)
                    .body("code", equalTo(header.toLowerCase(Locale.ROOT) + "-invalid"));
            for (var invalid : header.equals("User-Id") ? List.of("", "a".repeat(129)) : List.of("not-a-uuid", "1-1-1-1-1", "")) {
                h = headers(); h.put(header, invalid);
                given().headers(h).get(PATH).then().statusCode(400).body("status", equalTo(400))
                        .body("code", equalTo(header.toLowerCase(Locale.ROOT) + "-invalid"));
            }
        }
    }
    @Test void exactProcessSurvivesAsyncWorkAndConcurrentRequests() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = new ArrayList<Future<?>>();
            for (int index = 0; index < 12; index++) {
                var h = headers(); var process = h.get("Process-Id");
                calls.add(executor.submit(() -> given().headers(h).get(PATH).then().statusCode(200)
                        .header("Process-Id", equalTo(process)).body("data.value", equalTo(process))));
            }
            for (var call : calls) call.get(20, TimeUnit.SECONDS);
        }
        given().get(PATH).then().statusCode(400).header("Process-Id", nullValue());
        given().get("/q/health/ready").then().statusCode(200).header("Process-Id", nullValue());
    }
    @Test void processSurvivesKnownAndUnexpectedFailure() {
        for (var path : List.of("known-failure", "unexpected-failure")) {
            var h = headers();
            given().headers(h).get("/v1/_verification/" + path).then()
                    .statusCode(path.equals("known-failure") ? 400 : 500).header("Process-Id", equalTo(h.get("Process-Id")));
        }
    }
}
