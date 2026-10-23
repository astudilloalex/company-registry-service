package com.alexastudillo.companyregistry.infrastructure.database;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class DatabaseStartupTest {
    @Inject
    Flyway flyway;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @Test
    void cleanStartupMigratesAndHealthNeedsNoBusinessHeaders() throws Exception {
        given().when().get("/q/health/ready").then().statusCode(200);
        assertEquals("8", flyway.info().current().getVersion().getVersion());
        flyway.validate();
        try (var connection = flyway.getConfiguration().getDataSource().getConnection();
             var statement = connection.createStatement();
             var tables = statement.executeQuery("SELECT count(*) FROM information_schema.tables "
                     + "WHERE table_schema='public' AND table_type='BASE TABLE'")) {
            assertTrue(tables.next());
            assertEquals(7, tables.getInt(1));
        }
    }

    @Test
    void runtimeCanPerformDmlButCannotCreateSchemaObjects() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, "company_registry_runtime", "runtime_test");
             var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            assertEquals(1, statement.executeUpdate("INSERT INTO companies "
                    + "(tenant_id, party_id, code, operating_name, created_by, updated_by) "
                    + "VALUES (uuidv7(), uuidv7(), 'PERMISSION-TEST', 'PERMISSION TEST', 'test', 'test')"));
            assertEquals(1, statement.executeUpdate("UPDATE companies SET description='UPDATED' WHERE code='PERMISSION-TEST'"));
            try (var result = statement.executeQuery("SELECT count(*) FROM companies WHERE code='PERMISSION-TEST'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            assertEquals(1, statement.executeUpdate("DELETE FROM companies WHERE code='PERMISSION-TEST'"));
            connection.rollback();
            var failure = assertThrows(SQLException.class,
                    () -> statement.execute("CREATE TABLE forbidden_runtime_ddl (id integer)"));
            assertEquals("42501", failure.getSQLState());
            connection.rollback();
        }
        try (var connection = flyway.getConfiguration().getDataSource().getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='companies'")) {
            assertTrue(result.next());
            assertEquals("company_registry_migration", result.getString(1));
        }
    }

    @Test
    void repeatedStartupMigrationAndValidationLeaveHistoryUnchanged() throws Exception {
        var before = history();
        assertEquals(8, before.size());
        for (int restart = 0; restart < 2; restart++) {
            // A fresh engine repeats Flyway's startup lifecycle against the same persisted database.
            var restarted = Flyway.configure()
                    .dataSource(flyway.getConfiguration().getDataSource())
                    .locations("db/migration").cleanDisabled(true)
                    .validateOnMigrate(true).validateMigrationNaming(true).load();
            assertEquals(0, restarted.migrate().migrationsExecuted);
            restarted.validate();
            assertEquals(before, history());
        }
    }

    private List<List<Object>> history() throws Exception {
        try (var connection = flyway.getConfiguration().getDataSource().getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT installed_rank, version, description, type, script, "
                     + "checksum, installed_by, installed_on, execution_time, success "
                     + "FROM flyway_schema_history ORDER BY installed_rank")) {
            var rows = new ArrayList<List<Object>>();
            while (result.next()) {
                var row = new ArrayList<Object>();
                for (int column = 1; column <= 10; column++) {
                    row.add(result.getObject(column));
                }
                rows.add(row);
            }
            return rows;
        }
    }
}
