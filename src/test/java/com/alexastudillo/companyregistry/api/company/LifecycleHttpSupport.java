package com.alexastudillo.companyregistry.api.company;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;

abstract class LifecycleHttpSupport {
    @Inject
    Flyway flyway;
    final UUID tenant = UUID.randomUUID();
    final String process = UUID.randomUUID().toString();

    record Parent(UUID id, UUID party) {
        String path() {
            return "/v1/companies/" + id;
        }
    }

    @BeforeEach
    void resetProvider() {
        LifecycleReferenceStubResource.stub.reset();
    }

    RequestSpecification request() {
        return given().header("Tenant-Id", tenant).header("User-Id", "Actor-aB").header("Process-Id", process)
                .contentType("application/json");
    }

    Parent parent(String status) throws SQLException {
        var party = UUID.randomUUID();
        var id = UUID.fromString(scalar(
                "INSERT INTO companies(tenant_id,party_id,code,operating_name,status,created_by,updated_by) VALUES('"
                        + tenant + "','" + party + "','" + UUID.randomUUID().toString().toUpperCase() + "','COMPANY','"
                        + status
                        + "','Creator-aB','Creator-aB') RETURNING id"));
        var parent = new Parent(id, party);
        reply(parent, "LEGAL_ENTITY", "ACTIVE");
        return parent;
    }

    void reply(Parent parent, String kind, String state) {
        LifecycleReferenceStubResource.stub.reply("/v1/parties/" + parent.party(), 200,
                "{\"status\":200,\"code\":\"successful\",\"data\":{\"partyId\":\"" + parent.party()
                        + "\",\"type\":\"" + kind + "\",\"recordStatus\":\"" + state + "\"}}");
    }

    String scalar(String sql) throws SQLException {
        try (var c = flyway.getConfiguration().getDataSource().getConnection();
                var s = c.createStatement();
                var r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }

    String site(Parent parent, String type, String code) {
        return request().header("Idempotency-Key", UUID.randomUUID())
                .body("{\"code\":\"" + code + "\",\"name\":\"SITE\",\"siteType\":\"" + type + "\"}")
                .post(parent.path() + "/sites").then().statusCode(201).extract().path("data.siteId");
    }

    String headquarters(Parent parent) {
        String site = site(parent, "HEADQUARTERS", "HQ");
        request().header("If-Match", "0").post(parent.path() + "/sites/" + site + "/activate").then().statusCode(200);
        return site;
    }

    Response command(Parent parent, String operation, long version, String key, String body) {
        var request = request().header("If-Match", version);
        if (key != null)
            request.header("Idempotency-Key", key);
        if (body != null)
            request.body(body);
        return request.post(parent.path() + "/" + operation);
    }

    void error(Response response, int status, String code) {
        response.then().statusCode(status).header("Process-Id", process)
                .body("size()", equalTo(2), "status", equalTo(status), "code", equalTo(code));
    }

}
