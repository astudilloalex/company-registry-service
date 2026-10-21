package com.alexastudillo.companyregistry.api.site;

import static io.restassured.RestAssured.given;

import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.util.UUID;
import org.flywaydb.core.Flyway;

abstract class ContactHttpSupport {
    @Inject
    Flyway flyway;
    protected final UUID tenant = UUID.randomUUID();
    protected final String process = UUID.randomUUID().toString();

    protected record Fixture(UUID company, UUID site, String path) {
    }

    protected Connection db() throws Exception {
        return flyway.getConfiguration().getDataSource().getConnection();
    }

    protected Fixture fixture() throws Exception {
        try (var c = db(); var s = c.createStatement()) {
            UUID company;
            UUID site;
            try (var r = s.executeQuery(
                    "INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES('"
                            + tenant + "',uuidv7(),'" + UUID.randomUUID().toString().toUpperCase()
                            + "','PARENT','Creator','Creator') RETURNING id")) {
                r.next();
                company = r.getObject(1, UUID.class);
            }
            try (var r = s.executeQuery(
                    "INSERT INTO company_sites(tenant_id,company_id,code,name,created_by,updated_by) VALUES('"
                            + tenant + "','" + company + "','SITE','SEDE','Creator','Creator') RETURNING id")) {
                r.next();
                site = r.getObject(1, UUID.class);
            }
            return new Fixture(company, site, "/v1/companies/" + company + "/sites/" + site + "/contacts");
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger NUMBERS = new java.util.concurrent.atomic.AtomicInteger(
            1000);

    protected UUID seedContact(Fixture fixture, String type, boolean primary) throws Exception {
        String value = type.equals("EMAIL") ? UUID.randomUUID() + "@example.com"
                : "+1202555" + NUMBERS.incrementAndGet();
        return seedContact(fixture, type, value, primary);
    }

    protected UUID seedContact(Fixture fixture, String type, String value, boolean primary) throws Exception {
        try (var c = db();
                var s = c.createStatement();
                var r = s.executeQuery(
                        "INSERT INTO company_site_contact_points(site_id,contact_type,value,is_primary,created_by,updated_by,created_at,updated_at) VALUES('"
                                + fixture.site() + "','" + type + "','" + value + "'," + primary
                                + ",'Creator','Creator','2026-10-18T20:00:00.123456Z','2026-10-18T20:00:00.123456Z') RETURNING id")) {
            r.next();
            return r.getObject(1, UUID.class);
        }
    }

    protected RequestSpecification request() {
        return given().header("Tenant-Id", tenant).header("User-Id", "Actor-aB").header("Process-Id", process)
                .contentType("application/json");
    }

    protected RequestSpecification mutation(long version, String key) {
        var request = request().header("If-Match", version);
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    protected void sql(String sql) throws Exception {
        try (var c = db(); var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    protected long scalar(String sql) throws Exception {
        try (var c = db(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

}
