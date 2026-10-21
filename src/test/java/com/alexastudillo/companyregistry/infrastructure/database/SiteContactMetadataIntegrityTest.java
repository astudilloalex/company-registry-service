package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class SiteContactMetadataIntegrityTest extends SqlIntegritySupport {
    @Test
    void everyNamespaceEnforcesMetadataOnInsertionAndUpdate() throws Exception {
        for (String operation : new String[] {"company.site.contact.create.v1", "company.site.contact.update.v1",
                "company.site.contact.delete.v1"}) {
            execute(insert(operation));
            for (var invalid : new String[][] {{"site_id", "NULL"}, {"expected_aggregate_version", "NULL"},
                    {"expected_aggregate_version", "-1"}, {"fingerprint_version", "NULL"},
                    {"fingerprint_version", "0"}, {"fingerprint_key_id", "NULL"}, {"fingerprint_key_id", "'invalid key'"},
                    {"response_http_status", operation.endsWith("create.v1") ? "200" : "201"},
                    {"request_hash", "repeat('z',64)"}, {"result_snapshot", "'[]'::jsonb"},
                    {"result_snapshot_schema_version", "0"}}) {
                assertCheckViolation(insert(operation, invalid[0], invalid[1]));
                assertCheckViolation("UPDATE company_idempotency_records SET " + invalid[0] + "=" + invalid[1]
                        + " WHERE tenant_id='" + tenant + "' AND operation='" + operation + "'");
            }
            assertEquals("1", scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant
                    + "' AND operation='" + operation + "'"));
        }
    }

    @Test
    void pageIndexIsReadyValidPartialAndOrderedWithoutReplacingPrimaryProtection() throws Exception {
        var definition = scalar("SELECT pg_get_indexdef(indexrelid) FROM pg_index WHERE indexrelid='ix_company_site_contacts_page'::regclass");
        assertTrue(definition.contains("(site_id, created_at DESC, id DESC)"));
        assertTrue(definition.contains("WHERE (deleted_at IS NULL)"));
        assertEquals("true", scalar("SELECT (indisready AND indisvalid)::text FROM pg_index WHERE indexrelid='ix_company_site_contacts_page'::regclass"));
        assertEquals("true", scalar("SELECT (indisunique AND indisready AND indisvalid)::text FROM pg_index WHERE indexrelid='uq_company_site_primary_contact'::regclass"));
    }

    private void assertCheckViolation(String sql) throws Exception {
        var savepoint = connection.setSavepoint();
        try (var statement = connection.createStatement()) {
            var failure = assertThrows(SQLException.class, () -> statement.execute(sql));
            assertEquals("23514", failure.getSQLState());
        } finally {
            connection.rollback(savepoint);
        }
    }

    private String insert(String operation, String... overrides) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("tenant_id", "'" + tenant + "'");
        fields.put("company_id", "'" + company + "'");
        fields.put("site_id", "'" + site + "'");
        fields.put("operation", "'" + operation + "'");
        fields.put("idempotency_key", "'" + UUID.randomUUID() + "'");
        fields.put("expected_aggregate_version", "0");
        fields.put("response_http_status", operation.endsWith("create.v1") ? "201" : "200");
        fields.put("request_hash", "repeat('a',64)");
        fields.put("fingerprint_version", "1");
        fields.put("fingerprint_key_id", "'K1'");
        fields.put("result_snapshot_schema_version", "1");
        fields.put("result_snapshot", "'{\"original\":true}'::jsonb");
        fields.put("created_by", "'Actor-aB'");
        for (int i = 0; i < overrides.length; i += 2) fields.put(overrides[i], overrides[i + 1]);
        return "INSERT INTO company_idempotency_records(" + String.join(",", fields.keySet()) + ") VALUES("
                + String.join(",", fields.values()) + ")";
    }
}
