package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class SiteIntegrityTest extends SqlIntegritySupport {
    @Test
    void identityKeysAndQualifiedOwnership() throws Exception {
        assertEquals(7, site.version());
        assertEquals("0:DRAFT:BRANCH", scalar("SELECT version::text||':'||status::text||':'||site_type::text "
                + "FROM company_sites WHERE id='" + site + "'"));
        rejects("23505", "pk_company_sites", "INSERT INTO company_sites "
                + "SELECT id,tenant_id,company_id,'OTHER',name,site_type,status,version,created_at,created_by,updated_at,updated_by "
                + "FROM company_sites WHERE id='" + site + "'");
        rejects("23505", "uq_company_sites_code", siteInsert("SITE", "BRANCH", "DRAFT"));
        rejects("23503", "fk_company_sites_company", siteInsert("OTHER", "BRANCH", "DRAFT")
                .replace("'" + tenant + "'", "'" + UUID.randomUUID() + "'"));
        rejects("23503", "fk_company_sites_company", siteInsert("OTHER", "BRANCH", "DRAFT")
                .replace("'" + company + "'", "'" + UUID.randomUUID() + "'"));
        rejects("23001", "fk_company_sites_company", "DELETE FROM companies WHERE id='" + company + "'");
        assertEquals("UNIQUE (tenant_id, company_id, id)", scalar("SELECT pg_get_constraintdef(oid) "
                + "FROM pg_constraint WHERE conname='uq_company_sites_owner_id'"));
        assertEquals("FOREIGN KEY (tenant_id, company_id) REFERENCES companies(tenant_id, id) ON DELETE RESTRICT",
                scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='fk_company_sites_company'"));
        var other = uuid("INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) "
                + "VALUES('" + tenant + "',uuidv7(),'OTHER','OTHER','actor','actor') RETURNING id");
        execute(siteInsert("SITE", "BRANCH", "DRAFT").replace("'" + company + "'", "'" + other + "'"));
    }

    @Test
    void textVersionAndAuditChecks() throws Exception {
        canonical("company_sites", site, "ck_company_sites_code", "code", false);
        canonical("company_sites", site, "ck_company_sites_name", "name", false);
        check("company_sites", site, "ck_company_sites_version", "version=-1");
        check("company_sites", site, "ck_company_sites_updated_by", "updated_by='  '");
        check("company_sites", site, "ck_company_sites_audit_dates", "updated_at=created_at-interval '1 second'");
        rejects("23514", "ck_company_sites_created_by", siteInsert("NEW", "BRANCH", "DRAFT")
                .replace("'actor','actor'", "'  ','actor'"));
        execute("UPDATE company_sites SET version=1,updated_by='Actor-aB' WHERE id='" + site + "'");
        assertEquals("1:Actor-aB", scalar("SELECT version::text||':'||updated_by FROM company_sites WHERE id='" + site + "'"));
    }

    @Test
    void immutableIdentityAndClosedTerminal() throws Exception {
        for (var mutation : new String[]{"id=uuidv7()", "tenant_id=uuidv7()", "company_id=uuidv7()",
                "created_at=created_at-interval '1 second'", "created_by='changed'"}) {
            check("company_sites", site, "ck_company_sites_immutable_identity", mutation);
        }
        execute("UPDATE company_sites SET status='CLOSED' WHERE id='" + site + "'");
        execute("UPDATE company_sites SET status='CLOSED' WHERE id='" + site + "'");
        for (var mutation : new String[]{"status='DRAFT'", "status='ACTIVE'", "status='INACTIVE'", "name='CHANGED'", "version=1"}) {
            check("company_sites", site, "ck_company_sites_closed_terminal", mutation);
        }
        assertEquals("CLOSED", scalar("SELECT status::text FROM company_sites WHERE id='" + site + "'"));
    }

    @Test
    void headquartersPredicateAllowsClosedHistoryAndOneCurrentOfAnyState() throws Exception {
        for (var status : new String[]{"DRAFT", "ACTIVE", "INACTIVE"}) {
            var point = connection.setSavepoint();
            execute(siteInsert("OLD1", "HEADQUARTERS", "CLOSED"));
            execute(siteInsert("OLD2", "HEADQUARTERS", "CLOSED"));
            execute(siteInsert("CURRENT", "HEADQUARTERS", status));
            for (var other : new String[]{"DRAFT", "ACTIVE", "INACTIVE"}) {
                rejects("23505", "uq_company_sites_open_headquarters", siteInsert("COMPETITOR", "HEADQUARTERS", other));
            }
            execute(siteInsert("BRANCH2", "BRANCH", "ACTIVE"));
            assertEquals(status + ":CLOSED:CLOSED", scalar("SELECT string_agg(status::text,':' ORDER BY code) "
                    + "FROM company_sites WHERE company_id='" + company + "' AND site_type='HEADQUARTERS'"));
            connection.rollback(point);
            connection.releaseSavepoint(point);
        }
    }

    @Test
    void concurrentHeadquartersHaveExactlyOneCommit() throws Exception {
        // Separate committed fixture: neither contender shares the rollback-only fixture transaction.
        var owner = UUID.randomUUID();
        var legal = UUID.randomUUID();
        try (var first = flyway.getConfiguration().getDataSource().getConnection();
             var second = flyway.getConfiguration().getDataSource().getConnection();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (var statement = first.createStatement()) {
                statement.executeUpdate("INSERT INTO companies(id,tenant_id,party_id,code,operating_name,created_by,updated_by) "
                        + "VALUES('" + legal + "','" + owner + "',uuidv7(),'RACE','RACE','actor','actor')");
            }
            try {
                first.setAutoCommit(false);
                second.setAutoCommit(false);
                var base = "INSERT INTO company_sites(tenant_id,company_id,code,name,site_type,created_by,updated_by) "
                        + "VALUES('" + owner + "','" + legal + "','%s','HQ','HEADQUARTERS','actor','actor')";
                try (var statement = first.createStatement()) { statement.executeUpdate(base.formatted("FIRST")); }
                int backend;
                try (var statement = second.createStatement(); var row = statement.executeQuery("SELECT pg_backend_pid()")) {
                    assertTrue(row.next()); backend = row.getInt(1);
                }
                var contender = executor.submit(() -> {
                    try (var statement = second.createStatement()) {
                        statement.execute("SET LOCAL statement_timeout='8s'");
                        statement.executeUpdate(base.formatted("SECOND"));
                        second.commit();
                        return (SQLException) null;
                    } catch (SQLException failure) { second.rollback(); return failure; }
                });
                await().alias("Second independent transaction must actually wait on the unique-key lock")
                        .atMost(Duration.ofSeconds(5))
                        .pollInterval(Duration.ofMillis(20))
                        .until(() -> {
                            try (var statement = first.createStatement();
                                 var row = statement.executeQuery("SELECT cardinality(pg_blocking_pids(" + backend + "))>0")) {
                                return row.next() && row.getBoolean(1);
                            }
                        });
                first.commit();
                var failure = contender.get(10, TimeUnit.SECONDS);
                assertNotNull(failure, "Both contenders must not commit");
                assertDiagnostic(failure, "23505", "uq_company_sites_open_headquarters");
                try (var statement = first.createStatement();
                     var row = statement.executeQuery("SELECT count(*) FROM company_sites WHERE company_id='" + legal + "'")) {
                    assertTrue(row.next()); assertEquals(1, row.getInt(1));
                }
            } finally {
                first.rollback(); second.rollback(); first.setAutoCommit(true);
                try (var statement = first.createStatement()) {
                    statement.executeUpdate("DELETE FROM company_sites WHERE company_id='" + legal + "'");
                    statement.executeUpdate("DELETE FROM companies WHERE id='" + legal + "'");
                }
            }
        }
    }

    @Test
    void enumsIndexesAndTriggerDefinitions() throws Exception {
        assertEquals("DRAFT,ACTIVE,INACTIVE,CLOSED", scalar("SELECT string_agg(enumlabel,',' ORDER BY enumsortorder) FROM pg_enum WHERE enumtypid='company_site_status'::regtype"));
        assertEquals("HEADQUARTERS,BRANCH,WAREHOUSE,OTHER", scalar("SELECT string_agg(enumlabel,',' ORDER BY enumsortorder) FROM pg_enum WHERE enumtypid='company_site_type'::regtype"));
        rejects("22P02", null, "UPDATE company_sites SET status='UNKNOWN' WHERE id='" + site + "'");
        rejects("22P02", null, "UPDATE company_sites SET site_type='UNKNOWN' WHERE id='" + site + "'");
        index("ix_company_sites_tenant_status", "CREATE INDEX ix_company_sites_tenant_status ON public.company_sites USING btree (tenant_id, company_id, status, id)");
        index("ix_company_sites_type", "CREATE INDEX ix_company_sites_type ON public.company_sites USING btree (company_id, site_type)");
        assertEquals("O:reject_company_site_immutable_changes", scalar("SELECT t.tgenabled::text||':'||p.proname FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid WHERE t.tgrelid='company_sites'::regclass AND t.tgname='trg_company_sites_immutable_changes'"));
        var predicate = scalar("SELECT pg_get_expr(indpred,indrelid) FROM pg_index WHERE indexrelid='uq_company_sites_open_headquarters'::regclass");
        assertEquals("((site_type = 'HEADQUARTERS'::company_site_type) AND (status <> 'CLOSED'::company_site_status))", predicate);
    }
}
