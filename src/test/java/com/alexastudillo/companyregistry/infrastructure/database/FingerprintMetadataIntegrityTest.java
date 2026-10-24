package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class FingerprintMetadataIntegrityTest extends SqlIntegritySupport {
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "test.database.admin-user") String adminUser;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "test.database.admin-password") String adminPassword;
    private String insert(String operation, String version, String key) {
        return "INSERT INTO company_idempotency_records(tenant_id,operation,idempotency_key,request_hash,company_id,response_http_status,result_snapshot,created_by,fingerprint_version,fingerprint_key_id) VALUES('"
                + tenant + "','" + operation + "','" + UUID.randomUUID() + "',repeat('a',64),'" + company + "',201,'{\"typed\":true,\"optional\":null}'::jsonb,'Actor-aB'," + version + "," + key + ")";
    }
    @Test void enforcesMetadataPairsAndNewCreationShape() throws Exception {
        execute(insert("technical.t00", "NULL", "NULL"));
        execute(insert("company.create.v1", "1", "'K1'"));
        for (var pair : new String[][]{{"NULL","'K1'"},{"1","NULL"},{"0","'K1'"},{"-1","'K1'"},{"1","''"},{"1","'  '"}}) {
            rejects("23514", "ck_company_idempotency_fingerprint_metadata", insert("technical.t00", pair[0], pair[1]));
        }
        rejects("23514", "ck_company_idempotency_creation_metadata", insert("company.create.v1", "NULL", "NULL"));
        rejects("22001", null, insert("company.create.v1", "1", "repeat('k',65)"));
        rejects("23514", "ck_company_idempotency_creation_metadata", insert("company.create.v1", "1", "'K1'").replace(",201,", ",200,"));
        assertEquals("true", scalar("SELECT bool_and(fingerprint_version=1 AND fingerprint_key_id='K1')::text FROM company_idempotency_records WHERE tenant_id='"+tenant+"' AND operation='company.create.v1'"));
    }

    @Test void upgradesV1WithoutRewritingHistoricalSnapshotsAndRejectsUnknownHu01Provenance() throws Exception {
        var v1 = Files.readString(Path.of("src/main/resources/db/migration/V1__create_company_registry_schema.sql"));
        var v2 = Files.readString(Path.of("src/main/resources/db/migration/V2__add_idempotency_fingerprint_metadata.sql"));
        // Isolated transactional schema exercises the real V1->V2 SQL with existing T00 data.
        var jdbcUrl = connection.getMetaData().getURL();
        connection.rollback(); connection.close();
        connection = java.sql.DriverManager.getConnection(jdbcUrl, adminUser, adminPassword);
        connection.setAutoCommit(false);
        execute("CREATE SCHEMA fingerprint_upgrade"); execute("SET LOCAL search_path TO fingerprint_upgrade, public");
        try (var statement = connection.createStatement()) { statement.execute(v1); }
        execute("INSERT INTO companies(id,tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES('"+company+"','"+tenant+"',uuidv7(),'UPGRADE','UPGRADE','a','a')");
        var legacy = "INSERT INTO company_idempotency_records(tenant_id,operation,idempotency_key,request_hash,company_id,response_http_status,result_snapshot,created_by) VALUES('"+tenant+"','technical.t00','key',repeat('a',64),'"+company+"',201,'{\"opaque\":\"Logo-aB\",\"optional\":null}'::jsonb,'Actor-aB')";
        execute(legacy);
        execute(legacy.replace("technical.t00", "company.create.v1"));
        var savepoint = connection.setSavepoint();
        try (var statement = connection.createStatement()) {
            var failure = assertThrows(java.sql.SQLException.class, () -> statement.execute(v2));
            assertEquals("23514", failure.getSQLState());
        } finally { connection.rollback(savepoint); }
        execute("DELETE FROM company_idempotency_records WHERE operation='company.create.v1'");
        try (var statement = connection.createStatement()) { statement.execute(v2); }
        assertEquals("Logo-aB:true:true", scalar("SELECT (result_snapshot->>'opaque')||':'||(result_snapshot->'optional'='null'::jsonb)::text||':'||(fingerprint_version IS NULL AND fingerprint_key_id IS NULL)::text FROM company_idempotency_records"));
        assertEquals("6", scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema='fingerprint_upgrade' AND table_type='BASE TABLE'"));
        assertEquals("9", flyway.info().current().getVersion().getVersion());
        assertEquals("-272976494", scalar("SELECT checksum::text FROM public.flyway_schema_history WHERE version='1'"));
    }
}
