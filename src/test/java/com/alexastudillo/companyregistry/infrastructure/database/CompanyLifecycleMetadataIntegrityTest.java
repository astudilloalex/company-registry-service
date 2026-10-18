package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class CompanyLifecycleMetadataIntegrityTest extends SqlIntegritySupport {
    private String insert(String... overrides) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("tenant_id", "'" + tenant + "'");
        fields.put("operation", "'company.activate.v1'");
        fields.put("idempotency_key", "'" + UUID.randomUUID() + "'");
        fields.put("request_hash", "repeat('a',64)");
        fields.put("company_id", "'" + company + "'");
        fields.put("site_id", "NULL");
        fields.put("expected_aggregate_version", "0");
        fields.put("response_http_status", "200");
        fields.put("result_snapshot_schema_version", "1");
        fields.put("result_snapshot", "'{\"version\":0,\"description\":null,\"logoUrl\":null}'::jsonb");
        fields.put("created_by", "'Actor-aB'");
        fields.put("fingerprint_version", "1");
        fields.put("fingerprint_key_id", "'K1'");
        for (int i = 0; i < overrides.length; i += 2) fields.put(overrides[i], overrides[i + 1]);
        return "INSERT INTO company_idempotency_records(" + String.join(",", fields.keySet())
                + ") VALUES(" + String.join(",", fields.values()) + ")";
    }

    @Test
    void acceptsAllThreeOperationsAndPreservesEarlierNamespaces() throws Exception {
        for (var operation : new String[] {"company.activate.v1", "company.deactivate.v1", "company.archive.v1"}) {
            execute(insert("operation", "'" + operation + "'", "idempotency_key", "'shared'"));
        }
        execute(insert("operation", "'company.create.v1'", "site_id", "NULL", "expected_aggregate_version", "NULL", "response_http_status", "201"));
        execute(insert("operation", "'company.update.v1'", "site_id", "NULL"));
        execute(insert("operation", "'company.site.create.v1'", "site_id", "'" + site + "'", "expected_aggregate_version", "NULL", "response_http_status", "201"));
        execute(insert("operation", "'technical.t00'", "fingerprint_version", "NULL", "fingerprint_key_id", "NULL"));
        assertEquals("7", scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant + "'"));
    }

    @Test
    void rejectsEachInvalidMetadataOnInsertAndUpdate() throws Exception {
        for (var operation : new String[] {"company.activate.v1", "company.deactivate.v1", "company.archive.v1"}) {
            execute(insert("operation", "'" + operation + "'", "idempotency_key", "'original'"));
            for (var mutation : new String[][] {
                    {"site_id", "'" + site + "'", "ck_company_idempotency_lifecycle_metadata"},
                    {"fingerprint_version", "NULL", "ck_company_idempotency_fingerprint_metadata"},
                    {"fingerprint_version", "0", "ck_company_idempotency_fingerprint_metadata"},
                    {"fingerprint_version", "-1", "ck_company_idempotency_fingerprint_metadata"},
                    {"fingerprint_key_id", "NULL", "ck_company_idempotency_fingerprint_metadata"},
                    {"fingerprint_key_id", "''", "ck_company_idempotency_fingerprint_metadata"},
                    {"fingerprint_key_id", "' bad key '", "ck_company_idempotency_fingerprint_metadata"},
                    {"expected_aggregate_version", "NULL", "ck_company_idempotency_lifecycle_metadata"},
                    {"expected_aggregate_version", "-1", "ck_company_idempotency_expected_version"},
                    {"response_http_status", "201", "ck_company_idempotency_lifecycle_metadata"},
                    {"response_http_status", "299", "ck_company_idempotency_lifecycle_metadata"},
                    {"request_hash", "repeat('g',64)", "ck_company_idempotency_hash"},
                    {"request_hash", "repeat('A',64)", "ck_company_idempotency_hash"},
                    {"request_hash", "repeat('a',63)", "ck_company_idempotency_hash"},
                    {"result_snapshot", "'null'::jsonb", "ck_company_idempotency_snapshot"},
                    {"result_snapshot", "'[]'::jsonb", "ck_company_idempotency_snapshot"},
                    {"result_snapshot_schema_version", "0", "ck_company_idempotency_snapshot_version"},
                    {"result_snapshot_schema_version", "-1", "ck_company_idempotency_snapshot_version"}}) {
                rejects("23514", mutation[2], insert("operation", "'" + operation + "'", mutation[0], mutation[1]));
                rejects("23514", mutation[2], "UPDATE company_idempotency_records SET " + mutation[0] + "=" + mutation[1]
                        + " WHERE tenant_id='" + tenant + "'");
            }
            rejects("23514", "ck_company_idempotency_lifecycle_metadata",
                    insert("operation", "'" + operation + "'", "fingerprint_version", "NULL", "fingerprint_key_id", "NULL"));
            rejects("23514", "ck_company_idempotency_lifecycle_metadata",
                    "UPDATE company_idempotency_records SET fingerprint_version=NULL,fingerprint_key_id=NULL WHERE tenant_id='" + tenant + "'");
            assertEquals("1", scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant + "'"));
            execute("DELETE FROM company_idempotency_records WHERE tenant_id='" + tenant + "'");
        }
    }

    @Test
    void enforcesCompositeCompanyOwnershipAndPrimaryKeyOnInsertAndUpdate() throws Exception {
        var foreignCompany = uuid("INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) "
                + "VALUES(uuidv7(),uuidv7(),'FOREIGN','FOREIGN','a','a') RETURNING id");
        execute(insert("idempotency_key", "'original'"));
        for (var id : new UUID[] {UUID.randomUUID(), foreignCompany}) {
            rejects("23503", "fk_company_idempotency_company", insert("company_id", "'" + id + "'"));
            rejects("23503", "fk_company_idempotency_company", "UPDATE company_idempotency_records SET company_id='"
                    + id + "' WHERE tenant_id='" + tenant + "'");
        }
        rejects("23505", "pk_company_idempotency_records", insert("idempotency_key", "'original'"));
        assertEquals("1", scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant + "'"));
    }
}
