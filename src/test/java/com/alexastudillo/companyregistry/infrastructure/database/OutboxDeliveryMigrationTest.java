package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.sql.DriverManager;
import java.util.ArrayList;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class OutboxDeliveryMigrationTest {
    @ConfigProperty(name = "quarkus.datasource.jdbc.url") String jdbc;
    @ConfigProperty(name = "test.database.admin-user") String admin;
    @ConfigProperty(name = "test.database.admin-password") String password;

    @Test void upgradesV8WithoutChangingItsChecksumsHistoryOrOutboxAndRuntimeStillHasNoDdl() throws Exception {
        String schema = "outbox_upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(jdbc, admin, password); var statement = connection.createStatement()) {
            statement.execute("create schema " + schema + " authorization company_registry_migration");
        }
        try {
            var old = Flyway.configure().dataSource(jdbc, "company_registry_migration", "migration_test")
                    .schemas(schema).locations("db/migration").target("8").cleanDisabled(true).load();
            assertEquals(8, old.migrate().migrationsExecuted);
            try (var connection = DriverManager.getConnection(jdbc, "company_registry_migration", "migration_test");
                 var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                statement.execute("""
                        insert into company_outbox_events(tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,
                        payload,correlation_id,created_by,updated_by) values(uuidv7(),'COMPANY',uuidv7(),0,'company.created.v1',
                        '{"immutable":"snapshot"}',uuidv7()::text,'upgrade','upgrade')
                        """);
                String before = scalar(statement, "select jsonb_agg(to_jsonb(t))::text from company_outbox_events t");
                var history = history(statement);
                var upgraded = Flyway.configure().dataSource(jdbc, "company_registry_migration", "migration_test")
                        .schemas(schema).locations("db/migration").cleanDisabled(true).load();
                assertEquals(1, upgraded.migrate().migrationsExecuted);
                upgraded.validate();
                assertEquals("9", upgraded.info().current().getVersion().getVersion());
                assertEquals(history, history(statement));
                assertEquals(before, scalar(statement, "select jsonb_agg(to_jsonb(t))::text from company_outbox_events t"));
                assertEquals("1", scalar(statement, "select count(*) from pg_indexes where schemaname=current_schema() and indexname='ix_company_outbox_due_priority'"));
            }
        } finally {
            try (var connection = DriverManager.getConnection(jdbc, admin, password); var statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }
    private static String scalar(java.sql.Statement statement, String sql) throws Exception {
        try (var rows = statement.executeQuery(sql)) { if (!rows.next()) throw new IllegalStateException("Missing result"); return rows.getString(1); }
    }
    private static java.util.List<String> history(java.sql.Statement statement) throws Exception {
        var result = new ArrayList<String>();
        try (var rows = statement.executeQuery("select row_to_json(t)::text from flyway_schema_history t where version::int<=8 order by installed_rank")) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }
}
