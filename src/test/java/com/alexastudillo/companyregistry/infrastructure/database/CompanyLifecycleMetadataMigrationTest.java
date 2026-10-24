package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class CompanyLifecycleMetadataMigrationTest extends SqlIntegritySupport {
    @ConfigProperty(name = "test.database.admin-user") String adminUser;
    @ConfigProperty(name = "test.database.admin-password") String adminPassword;

    @Test
    void upgradesV5WithoutRewritingHistoryAndDiagnosesAllThreeIncompatibleNamespaces() throws Exception {
        var jdbcUrl = connection.getMetaData().getURL();
        connection.rollback();
        connection.close();
        connection = DriverManager.getConnection(jdbcUrl, adminUser, adminPassword);
        connection.setAutoCommit(false);
        execute("CREATE SCHEMA company_lifecycle_upgrade");
        execute("SET LOCAL search_path TO company_lifecycle_upgrade, public");
        for (var migration : new String[] {"V1__create_company_registry_schema.sql",
                "V2__add_idempotency_fingerprint_metadata.sql", "V3__enforce_company_update_completion_metadata.sql",
                "V4__add_site_registration_support.sql", "V5__enforce_site_mutation_completion_metadata.sql"}) {
            runMigration(migration);
        }
        execute("INSERT INTO companies(id,tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES('"
                + company + "','" + tenant + "',uuidv7(),'UPGRADE','UPGRADE','Actor-aB','Actor-aB')");
        execute("INSERT INTO company_sites(id,tenant_id,company_id,code,name,created_by,updated_by) VALUES('"
                + site + "','" + tenant + "','" + company + "','SITE','SEDE','Actor-aB','Actor-aB')");
        for (var operation : new String[] {"company.create.v1", "company.update.v1", "company.site.create.v1", "company.site.update.v1", "company.site.activate.v1", "company.site.deactivate.v1", "company.site.close.v1"}) {
            boolean creation = operation.equals("company.create.v1") || operation.equals("company.site.create.v1");
            execute(completion(operation, operation, operation.startsWith("company.site.") ? "'" + site + "'" : "NULL",
                    creation ? "NULL" : "0", creation ? "201" : "200", "1", "'K1'"));
        }
        execute("INSERT INTO company_outbox_events(tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,payload,"
                + "correlation_id,created_by,updated_by) VALUES('" + tenant + "','COMPANY_SITE','" + site
                + "',0,'company.site.created.v1','{\"opaque\":\"Original-aB\"}'::jsonb,'Process-aB','Actor-aB','Actor-aB')");
        var companies = tableSnapshot("companies");
        execute("INSERT INTO company_site_addresses(site_id,country_code,address_line_1,created_by,updated_by) VALUES('"
                + site + "','EC','ADDRESS','Actor-aB','Actor-aB')");
        execute("INSERT INTO company_site_contact_points(site_id,contact_type,value,created_by,updated_by) VALUES('"
                + site + "','EMAIL','User@example.com','Actor-aB','Actor-aB')");
        var addresses = tableSnapshot("company_site_addresses");
        var contacts = tableSnapshot("company_site_contact_points");
        var sites = tableSnapshot("company_sites");
        var completions = tableSnapshot("company_idempotency_records");
        var events = tableSnapshot("company_outbox_events");
        var v6 = Files.readString(Path.of("src/main/resources/db/migration/V6__enforce_company_lifecycle_completion_metadata.sql"));
        for (var operation : new String[] {"company.activate.v1", "company.deactivate.v1", "company.archive.v1"}) {
            var fixture = connection.setSavepoint();
            execute(completion(operation, "legacy", "NULL", "NULL", "200", "NULL", "NULL"));
            var original = tableSnapshot("company_idempotency_records");
            var attempt = connection.setSavepoint();
            try (var statement = connection.createStatement()) {
                var failure = assertThrows(SQLException.class, () -> statement.execute(v6));
                assertEquals("23514", failure.getSQLState());
                assertTrue(failure.getMessage().contains("HU03 completion metadata"));
                assertTrue(failure.getMessage().contains("retain original snapshots"));
            } finally {
                connection.rollback(attempt);
            }
            assertEquals(original, tableSnapshot("company_idempotency_records"));
            assertEquals("0", scalar("SELECT count(*) FROM pg_constraint WHERE conname='ck_company_idempotency_lifecycle_metadata' "
                    + "AND connamespace='company_lifecycle_upgrade'::regnamespace"));
            connection.rollback(fixture);
        }
        runMigration("V6__enforce_company_lifecycle_completion_metadata.sql");
        assertEquals(companies, tableSnapshot("companies"));
        assertEquals(addresses, tableSnapshot("company_site_addresses"));
        assertEquals(contacts, tableSnapshot("company_site_contact_points"));
        assertEquals(sites, tableSnapshot("company_sites"));
        assertEquals(completions, tableSnapshot("company_idempotency_records"));
        assertEquals(events, tableSnapshot("company_outbox_events"));
        assertEquals("6", scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema='company_lifecycle_upgrade' AND table_type='BASE TABLE'"));
        assertEquals("1", scalar("SELECT count(*) FROM pg_constraint WHERE conname='ck_company_idempotency_lifecycle_metadata' "
                + "AND connamespace='company_lifecycle_upgrade'::regnamespace AND convalidated"));
        assertEquals("9", flyway.info().current().getVersion().getVersion());
        flyway.validate();
    }

    private void runMigration(String name) throws Exception {
        var source = Files.readString(Path.of("src/main/resources/db/migration", name));
        try (var statement = connection.createStatement()) {
            statement.execute(source);
        }
    }

    private String tableSnapshot(String table) throws SQLException {
        return scalar("SELECT jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text FROM " + table + " t");
    }

    private String completion(String operation, String key, String siteId, String expectedVersion,
            String status, String fingerprintVersion, String keyId) {
        return "INSERT INTO company_idempotency_records(tenant_id,operation,idempotency_key,request_hash,company_id,site_id,"
                + "expected_aggregate_version,response_http_status,result_snapshot,created_by,fingerprint_version,fingerprint_key_id) VALUES('"
                + tenant + "','" + operation + "','" + key + "',repeat('a',64),'" + company + "'," + siteId + ","
                + expectedVersion + "," + status + ",'{\"opaque\":\"Logo-aB\",\"version\":0,\"optional\":null}'::jsonb,'Actor-aB',"
                + fingerprintVersion + "," + keyId + ")";
    }
}
