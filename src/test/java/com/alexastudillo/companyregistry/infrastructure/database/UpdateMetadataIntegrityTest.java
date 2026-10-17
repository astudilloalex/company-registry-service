package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class UpdateMetadataIntegrityTest extends SqlIntegritySupport {
    private String insert(String... overrides) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("tenant_id", "'" + tenant + "'");
        fields.put("operation", "'company.update.v1'");
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
    void acceptsChangeAndNoopSnapshotsWithoutChangingPriorNamespaces() throws Exception {
        execute(insert("idempotency_key", "'noop'"));
        execute(insert("idempotency_key", "'change'", "expected_aggregate_version", "9223372036854775807",
                "result_snapshot", "'{\"version\":1,\"description\":\"CHANGED\",\"logoUrl\":null}'::jsonb"));
        execute(insert("operation", "'technical.t00'", "fingerprint_version", "NULL", "fingerprint_key_id", "NULL",
                "expected_aggregate_version", "NULL", "site_id", "'" + site + "'", "response_http_status", "299"));
        execute(insert("operation", "'company.create.v1'", "expected_aggregate_version", "NULL", "response_http_status", "201"));
        assertEquals("true", scalar("SELECT bool_and(response_http_status=200 AND site_id IS NULL "
                + "AND fingerprint_version=1 AND fingerprint_key_id='K1' AND expected_aggregate_version>=0)"
                + "::text FROM company_idempotency_records WHERE tenant_id='" + tenant + "' AND operation='company.update.v1'"));
    }

    @Test
    void requiresUpdateMetadataOnInsertAndUpdate() throws Exception {
        for (var field : new String[] {"fingerprint_version", "fingerprint_key_id", "expected_aggregate_version"}) {
            String overrides = field.equals("expected_aggregate_version") ? insert(field, "NULL")
                    : insert("fingerprint_version", "NULL", "fingerprint_key_id", "NULL");
            rejects("23514", "ck_company_idempotency_update_metadata", overrides);
        }
        for (var status : new String[] {"201", "299"}) {
            rejects("23514", "ck_company_idempotency_update_metadata", insert("response_http_status", status));
        }
        rejects("23514", "ck_company_idempotency_update_metadata", insert("site_id", "'" + site + "'"));
        execute(insert("idempotency_key", "'original'"));
        rejects("23514", "ck_company_idempotency_update_metadata",
                "UPDATE company_idempotency_records SET expected_aggregate_version=NULL WHERE tenant_id='" + tenant + "'");
        assertEquals("0", scalar("SELECT expected_aggregate_version::text FROM company_idempotency_records WHERE tenant_id='" + tenant + "'"));
    }

    @Test
    void retainsHashSnapshotVersionProvenanceAndTenantConstraints() throws Exception {
        for (var pair : new String[][] {{"0", "'K1'"}, {"-1", "'K1'"}, {"NULL", "'K1'"}, {"1", "NULL"},
                {"1", "''"}, {"1", "' bad key '"}}) {
            rejects("23514", "ck_company_idempotency_fingerprint_metadata",
                    insert("fingerprint_version", pair[0], "fingerprint_key_id", pair[1]));
        }
        rejects("23514", "ck_company_idempotency_expected_version", insert("expected_aggregate_version", "-1"));
        for (var hash : new String[] {"repeat('A',64)", "repeat('g',64)", "repeat('a',63)"}) {
            rejects("23514", "ck_company_idempotency_hash", insert("request_hash", hash));
        }
        for (var snapshot : new String[] {"'null'::jsonb", "'[]'::jsonb", "'1'::jsonb"}) {
            rejects("23514", "ck_company_idempotency_snapshot", insert("result_snapshot", snapshot));
        }
        for (var version : new String[] {"0", "-1"}) {
            rejects("23514", "ck_company_idempotency_snapshot_version", insert("result_snapshot_schema_version", version));
        }
        rejects("23503", "fk_company_idempotency_company", insert("tenant_id", "'" + UUID.randomUUID() + "'"));
        rejects("23503", "fk_company_idempotency_company", insert("company_id", "'" + UUID.randomUUID() + "'"));
        execute(insert("idempotency_key", "'same'"));
        rejects("23505", "pk_company_idempotency_records", insert("idempotency_key", "'same'"));
        assertEquals("1", scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant + "' AND idempotency_key='same'"));
    }
}
