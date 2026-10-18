package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class UpdateMetadataMigrationTest extends SqlIntegritySupport {
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "test.database.admin-user") String adminUser;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "test.database.admin-password") String adminPassword;

    @Test
    void upgradesV2PreservingHu01AndDiagnosesIncompatibleHistoricalUpdates() throws Exception {
        var jdbcUrl = connection.getMetaData().getURL();
        connection.rollback(); connection.close();
        connection = java.sql.DriverManager.getConnection(jdbcUrl, adminUser, adminPassword);
        connection.setAutoCommit(false);
        execute("CREATE SCHEMA update_upgrade");
        execute("SET LOCAL search_path TO update_upgrade, public");
        for (var migration : new String[] {"V1__create_company_registry_schema.sql", "V2__add_idempotency_fingerprint_metadata.sql"}) {
            try (var statement = connection.createStatement()) {
                statement.execute(Files.readString(Path.of("src/main/resources/db/migration", migration)));
            }
        }
        execute("INSERT INTO companies(id,tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES('"
                + company + "','" + tenant + "',uuidv7(),'UPGRADE','UPGRADE','a','a')");
        execute("INSERT INTO company_idempotency_records(tenant_id,operation,idempotency_key,request_hash,company_id,"
                + "response_http_status,result_snapshot,created_by,fingerprint_version,fingerprint_key_id) VALUES('"
                + tenant + "','company.create.v1','create',repeat('a',64),'" + company
                + "',201,'{\"company\":{\"version\":0},\"party\":{\"opaque\":\"Logo-aB\"},\"optional\":null}'::jsonb,'Actor-aB',1,'K1')");
        execute("INSERT INTO company_outbox_events(tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,payload,correlation_id,created_by,updated_by) VALUES('"
                + tenant + "','COMPANY','" + company + "',0,'company.created.v1','{\"opaque\":\"Original-aB\"}'::jsonb,'Process-aB','Actor-aB','Actor-aB')");
        var before = scalar("SELECT row_to_json(c)::text FROM companies c WHERE id='" + company + "'");
        var completion = scalar("SELECT row_to_json(c)::text FROM company_idempotency_records c");
        var event = scalar("SELECT row_to_json(c)::text FROM company_outbox_events c");
        execute("INSERT INTO company_idempotency_records(tenant_id,operation,idempotency_key,request_hash,company_id,response_http_status,result_snapshot,created_by) VALUES('"
                + tenant + "','company.update.v1','legacy',repeat('a',64),'" + company + "',200,'{}'::jsonb,'actor')");
        var v3 = Files.readString(Path.of("src/main/resources/db/migration/V3__enforce_company_update_completion_metadata.sql"));
        var savepoint = connection.setSavepoint();
        try (var statement = connection.createStatement()) {
            var failure = assertThrows(java.sql.SQLException.class, () -> statement.execute(v3));
            assertEquals("23514", failure.getSQLState());
            assertTrue(failure.getMessage().contains("HU02 completion metadata"));
        } finally { connection.rollback(savepoint); }
        assertEquals("1", scalar("SELECT count(*) FROM company_idempotency_records WHERE operation='company.update.v1'"));
        // Fixture owner explicitly supplies the known historical provenance; migration never does.
        execute("UPDATE company_idempotency_records SET fingerprint_version=1,fingerprint_key_id='K1',expected_aggregate_version=0 WHERE operation='company.update.v1'");
        try (var statement = connection.createStatement()) { statement.execute(v3); }
        assertEquals(before, scalar("SELECT row_to_json(c)::text FROM companies c WHERE id='" + company + "'"));
        assertEquals(completion, scalar("SELECT row_to_json(c)::text FROM company_idempotency_records c WHERE operation='company.create.v1'"));
        assertEquals(event, scalar("SELECT row_to_json(c)::text FROM company_outbox_events c"));
        assertEquals("6", scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema='update_upgrade' AND table_type='BASE TABLE'"));
        assertEquals("6", flyway.info().current().getVersion().getVersion());
        assertEquals("-272976494", scalar("SELECT checksum::text FROM public.flyway_schema_history WHERE version='1'"));
    }
}
