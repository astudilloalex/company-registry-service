package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class IdempotencyIntegrityTest extends SqlIntegritySupport {
    private static final String TABLE = "company_idempotency_records";
    private static final String PREFIX = "ck_company_idempotency_";

    private String insert(String... overrides) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("tenant_id", "'" + tenant + "'");
        fields.put("operation", "'company.create'");
        fields.put("idempotency_key", "'Key-aB'");
        fields.put("request_hash", "repeat('a',64)");
        fields.put("company_id", "'" + company + "'");
        fields.put("site_id", "NULL");
        fields.put("response_http_status", "201");
        fields.put("result_snapshot", "'{\"opaque\":\"Mi.Usuario@example.com\",\"key\":\"Key-aB\"}'::jsonb");
        fields.put("created_by", "'Actor-aB'");
        for (int i = 0; i < overrides.length; i += 2) fields.put(overrides[i], overrides[i + 1]);
        return "INSERT INTO " + TABLE + "(" + String.join(",", fields.keySet()) + ") VALUES(" + String.join(",", fields.values()) + ")";
    }

    @Test
    void compositeKeyAndOriginalSnapshot() throws Exception {
        execute(insert());
        assertEquals("Key-aB:1:Mi.Usuario@example.com:Actor-aB", scalar("SELECT idempotency_key||':'||result_snapshot_schema_version::text||':'||"
                + "(result_snapshot->>'opaque')||':'||created_by FROM " + TABLE + " WHERE tenant_id='" + tenant + "'"));
        assertEquals("true", scalar("SELECT (site_id IS NULL AND expected_aggregate_version IS NULL)::text FROM " + TABLE + " WHERE tenant_id='" + tenant + "'"));
        rejects("23505", "pk_company_idempotency_records", insert());
        execute(insert("operation", "'site.create'", "site_id", "'" + site + "'"));
        execute(insert("idempotency_key", "'Key-ab'"));
        execute(insert("idempotency_key", "'version-zero'", "expected_aggregate_version", "0"));
        execute(insert("idempotency_key", "'version-positive'", "expected_aggregate_version", "9223372036854775807"));
        assertEquals("PRIMARY KEY (tenant_id, operation, idempotency_key)", scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='pk_company_idempotency_records'"));
    }

    @Test
    void qualifiedReferencesRejectOtherTenantCompanyAndUnknownSite() throws Exception {
        rejects("23503", "fk_company_idempotency_company", insert("tenant_id", "'" + UUID.randomUUID() + "'"));
        rejects("23503", "fk_company_idempotency_company", insert("company_id", "'" + UUID.randomUUID() + "'"));
        rejects("23503", "fk_company_idempotency_site", insert("site_id", "'" + UUID.randomUUID() + "'"));
        var otherCompany = uuid("INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) "
                + "VALUES('" + tenant + "',uuidv7(),'OTHER','OTHER','actor','actor') RETURNING id");
        rejects("23503", "fk_company_idempotency_site", insert("company_id", "'" + otherCompany + "'", "site_id", "'" + site + "'"));
        var otherTenant = UUID.randomUUID();
        var foreignCompany = uuid("INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) "
                + "VALUES('" + otherTenant + "',uuidv7(),'OTHER','OTHER','actor','actor') RETURNING id");
        var foreignSite = uuid(siteInsert("FOREIGN", "BRANCH", "DRAFT").replace(tenant.toString(), otherTenant.toString())
                .replace(company.toString(), foreignCompany.toString()) + " RETURNING id");
        rejects("23503", "fk_company_idempotency_site", insert("site_id", "'" + foreignSite + "'"));
        execute(insert("tenant_id", "'" + otherTenant + "'", "company_id", "'" + foreignCompany + "'", "site_id", "'" + foreignSite + "'"));
        execute(insert("site_id", "'" + site + "'"));
        rejects("23001", "fk_company_idempotency_site", "DELETE FROM company_sites WHERE id='" + site + "'");
        // Site-less Company isolates the completion FK from the Site->Company FK.
        execute(insert("idempotency_key", "'other'", "company_id", "'" + otherCompany + "'"));
        rejects("23001", "fk_company_idempotency_company", "DELETE FROM companies WHERE id='" + otherCompany + "'");
        assertEquals("FOREIGN KEY (tenant_id, company_id, site_id) REFERENCES company_sites(tenant_id, company_id, id) ON DELETE RESTRICT",
                scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='fk_company_idempotency_site'"));
        index("ix_company_idempotency_owner", "CREATE INDEX ix_company_idempotency_owner ON public.company_idempotency_records USING btree (tenant_id, company_id, site_id)");
    }

    @Test
    void everyCheckAndJsonParserRejectInvalidCompletion() throws Exception {
        for (var field : new String[]{"operation", "idempotency_key", "created_by"}) {
            rejects("23514", PREFIX + (field.equals("idempotency_key") ? "key" : field), insert(field, "'  '"));
        }
        for (var hash : new String[]{"repeat('A',64)", "repeat('g',64)", "repeat('a',63)"}) {
            rejects("23514", PREFIX + "hash", insert("request_hash", hash));
        }
        rejects("23514", PREFIX + "expected_version", insert("expected_aggregate_version", "-1"));
        for (var status : new String[]{"199", "300"}) rejects("23514", PREFIX + "http_status", insert("response_http_status", status));
        for (var version : new String[]{"0", "-1"}) rejects("23514", PREFIX + "snapshot_version", insert("result_snapshot_schema_version", version));
        for (var json : new String[]{"'null'::jsonb", "'[]'::jsonb", "'1'::jsonb", "'\"text\"'::jsonb"}) {
            rejects("23514", PREFIX + "snapshot", insert("result_snapshot", json));
        }
        rejects("22P02", null, insert("result_snapshot", "'{invalid'::jsonb"));
        for (var status : new String[]{"200", "299"}) {
            execute(insert("idempotency_key", "'status-" + status + "'", "response_http_status", status, "result_snapshot_schema_version", "2"));
        }
        assertEquals("200:299", scalar("SELECT string_agg(response_http_status::text,':' ORDER BY response_http_status) FROM "
                + TABLE + " WHERE tenant_id='" + tenant + "' AND idempotency_key LIKE 'status-%'"));
    }

    @Test
    void mandatoryFieldsRejectSqlNull() throws Exception {
        for (var field : new String[]{"tenant_id", "operation", "idempotency_key", "request_hash", "company_id",
                "response_http_status", "result_snapshot_schema_version", "result_snapshot", "created_at", "created_by"}) {
            rejects("23502", null, insert(field, "NULL"));
        }
        assertEquals("0", scalar("SELECT count(*) FROM " + TABLE + " WHERE tenant_id='" + tenant + "'"));
    }
}
