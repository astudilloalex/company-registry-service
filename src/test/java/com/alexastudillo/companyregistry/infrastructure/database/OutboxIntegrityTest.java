package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class OutboxIntegrityTest extends SqlIntegritySupport {
    private static final String TABLE = "company_outbox_events";
    private static final String PREFIX = "ck_company_outbox_";
    private static final String[] EVENTS = {"company.created.v1", "company.updated.v1", "company.activated.v1",
            "company.deactivated.v1", "company.site.created.v1", "company.site.updated.v1", "company.site.closed.v1"};

    private String insert(String... overrides) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("tenant_id", "'" + tenant + "'");
        fields.put("aggregate_type", "'COMPANY'");
        fields.put("aggregate_id", "'" + company + "'");
        fields.put("aggregate_version", "0");
        fields.put("event_type", "'company.created.v1'");
        fields.put("payload", "'{\"key\":\"Key-aB\",\"url\":\"https://host/LogoAb.png\"}'::jsonb");
        fields.put("correlation_id", "'Process-aB'");
        fields.put("created_by", "'Actor-aB'");
        fields.put("updated_by", "'Actor-aB'");
        for (int i = 0; i < overrides.length; i += 2) fields.put(overrides[i], overrides[i + 1]);
        return "INSERT INTO " + TABLE + "(" + String.join(",", fields.keySet()) + ") VALUES(" + String.join(",", fields.values()) + ")";
    }

    @Test
    void defaultMetadataOpaquePayloadAndIdentity() throws Exception {
        var event = uuid(insert() + " RETURNING id");
        assertEquals(7, event.version());
        assertEquals("PENDING:0:0:0:1:Key-aB:https://host/LogoAb.png:Process-aB:Actor-aB", scalar("SELECT status::text||':'||"
                + "publish_attempts::text||':'||version::text||':'||aggregate_version::text||':'||event_schema_version::text||':'||"
                + "(payload->>'key')||':'||(payload->>'url')||':'||correlation_id||':'||created_by FROM " + TABLE + " WHERE id='" + event + "'"));
        rejects("23505", "pk_company_outbox_events", "INSERT INTO " + TABLE + " SELECT * FROM " + TABLE + " WHERE id='" + event + "'");
        rejects("23505", "uq_company_outbox_event_identity", insert());
        execute(insert("tenant_id", "'" + UUID.randomUUID() + "'"));
        execute(insert("aggregate_id", "'" + UUID.randomUUID() + "'"));
        execute("UPDATE " + TABLE + " SET version=1,causation_id='Cause-aB',last_attempt_at=now(),next_attempt_at=now()+interval '5 seconds',"
                + "last_error_detail='Diagnostic-aB' WHERE id='" + event + "'");
        assertEquals("0:1:Cause-aB:Diagnostic-aB", scalar("SELECT aggregate_version::text||':'||version::text||':'||causation_id||':'||last_error_detail FROM " + TABLE + " WHERE id='" + event + "'"));
        assertEquals("UNIQUE (tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type)", scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='uq_company_outbox_event_identity'"));
    }

    @Test
    void everyEventHasCorrectAggregateAndCreationVersion() throws Exception {
        for (var event : EVENTS) {
            var type = event.startsWith("company.site.") ? "COMPANY_SITE" : "COMPANY";
            var wrong = type.equals("COMPANY") ? "COMPANY_SITE" : "COMPANY";
            var version = event.endsWith("created.v1") ? "0" : "1";
            execute(insert("event_type", "'" + event + "'", "aggregate_type", "'" + type + "'", "aggregate_version", version));
            rejects("23514", PREFIX + "aggregate_mapping", insert("event_type", "'" + event + "'", "aggregate_type", "'" + wrong + "'", "aggregate_version", version));
            for (var invalid : event.endsWith("created.v1") ? new String[]{"-1", "1"} : new String[]{"-1", "0"}) {
                rejects("23514", PREFIX + "creation_version", insert("event_type", "'" + event + "'", "aggregate_type", "'" + type + "'", "aggregate_version", invalid));
            }
        }
        // An unknown event also violates mapping; CHECK ordering chooses aggregate_mapping first.
        // Inspect the event allow-list independently, then assert rejection without assuming CHECK order.
        var definition = scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='ck_company_outbox_event_type'");
        for (var event : EVENTS) assertTrue(definition.contains("'" + event + "'"));
        assertEquals(EVENTS.length, java.util.regex.Pattern.compile("'company\\.[^']+'::").matcher(definition).results().count());
        rejects("23514", null, insert("event_type", "'company.unknown.v1'", "aggregate_version", "1"));
    }

    @Test
    void jsonVersionsCorrelationAttemptsAndAuditChecks() throws Exception {
        for (var version : new String[]{"0", "-1"}) rejects("23514", PREFIX + "schema_version", insert("event_schema_version", version));
        for (var json : new String[]{"'null'::jsonb", "'[]'::jsonb", "'1'::jsonb", "'\"text\"'::jsonb"}) {
            rejects("23514", PREFIX + "payload", insert("payload", json));
        }
        rejects("22P02", null, insert("payload", "'{invalid'::jsonb"));
        for (var field : new String[]{"correlation_id", "causation_id", "created_by", "updated_by"}) {
            rejects("23514", PREFIX + field, insert(field, "'  '"));
        }
        rejects("23514", PREFIX + "attempts", insert("publish_attempts", "-1"));
        rejects("23514", PREFIX + "version", insert("version", "-1"));
        rejects("23514", PREFIX + "audit_dates", insert("updated_at", "now()-interval '1 second'"));
        execute(insert("event_schema_version", "2", "publish_attempts", "1", "version", "3", "causation_id", "'Cause-aB'"));
        assertEquals("2:1:3:Cause-aB", scalar("SELECT event_schema_version::text||':'||publish_attempts::text||':'||version::text||':'||causation_id FROM "
                + TABLE + " WHERE tenant_id='" + tenant + "'"));
    }

    @Test
    void publicationAndFailureMetadataMatchStatus() throws Exception {
        rejects("23514", PREFIX + "published_at", insert("status", "'PUBLISHED'"));
        for (var status : new String[]{"PENDING", "FAILED"}) {
            rejects("23514", PREFIX + "published_at", insert("status", "'" + status + "'", "published_at", "now()", "last_error_code", "'provider-unavailable'"));
        }
        rejects("23514", PREFIX + "failed_error", insert("status", "'FAILED'"));
        rejects("23514", PREFIX + "failed_error", insert("status", "'FAILED'", "last_error_code", "'  '"));
        execute(insert("status", "'PUBLISHED'", "published_at", "now()"));
        execute(insert("aggregate_id", "'" + UUID.randomUUID() + "'", "status", "'FAILED'", "last_error_code", "'provider-unavailable'"));
        execute(insert("aggregate_id", "'" + UUID.randomUUID() + "'", "status", "'PENDING'"));
        assertEquals("FAILED:PENDING:PUBLISHED", scalar("SELECT string_agg(status::text,':' ORDER BY status::text) FROM "
                + TABLE + " WHERE tenant_id='" + tenant + "'"));
    }

    @Test
    void enumsMandatoryFieldsAndPollingIndexes() throws Exception {
        assertEquals("COMPANY,COMPANY_SITE", scalar("SELECT string_agg(enumlabel,',' ORDER BY enumsortorder) FROM pg_enum WHERE enumtypid='company_outbox_aggregate_type'::regtype"));
        assertEquals("PENDING,PUBLISHED,FAILED", scalar("SELECT string_agg(enumlabel,',' ORDER BY enumsortorder) FROM pg_enum WHERE enumtypid='outbox_status'::regtype"));
        rejects("22P02", null, insert("aggregate_type", "'UNKNOWN'"));
        rejects("22P02", null, insert("status", "'UNKNOWN'"));
        for (var field : new String[]{"id", "tenant_id", "aggregate_type", "aggregate_id", "aggregate_version", "event_type", "event_schema_version",
                "payload", "occurred_at", "correlation_id", "status", "publish_attempts", "version", "created_at", "created_by", "updated_at", "updated_by"}) {
            rejects("23502", null, insert(field, "NULL"));
        }
        index("ix_company_outbox_delivery", "CREATE INDEX ix_company_outbox_delivery ON public.company_outbox_events USING btree (status, next_attempt_at, created_at, id)");
        index("ix_company_outbox_correlation", "CREATE INDEX ix_company_outbox_correlation ON public.company_outbox_events USING btree (correlation_id)");
    }
}
