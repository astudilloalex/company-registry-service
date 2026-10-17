package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.util.PSQLException;

/** Test-only SQL boundary with isolated transaction/savepoints and exact server diagnostics. */
abstract class SqlIntegritySupport {
    @Inject Flyway flyway;
    Connection connection;
    UUID tenant;
    UUID company;
    UUID site;

    @BeforeEach
    void startTransaction() throws Exception {
        connection = flyway.getConfiguration().getDataSource().getConnection();
        connection.setAutoCommit(false);
        tenant = UUID.randomUUID();
        company = uuid("INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) "
                + "VALUES('" + tenant + "',uuidv7(),'COMPANY','COMPAÑÍA','actor','actor') RETURNING id");
        site = uuid(siteInsert("SITE", "BRANCH", "DRAFT") + " RETURNING id");
    }

    @AfterEach
    void stopTransaction() throws Exception {
        if (connection != null) {
            try { connection.rollback(); } finally { connection.close(); }
        }
    }

    String siteInsert(String code, String type, String status) {
        return "INSERT INTO company_sites(tenant_id,company_id,code,name,site_type,status,created_by,updated_by) "
                + "VALUES('" + tenant + "','" + company + "','" + code + "','SEDE','" + type + "','" + status + "','actor','actor')";
    }

    void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.executeUpdate(sql); }
    }

    String scalar(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(sql)) {
            assertTrue(row.next(), sql);
            var result = row.getString(1);
            assertFalse(row.next(), sql);
            return result;
        }
    }

    UUID uuid(String sql) throws SQLException { return UUID.fromString(scalar(sql)); }

    void rejects(String state, String constraint, String sql) throws Exception {
        var savepoint = connection.setSavepoint();
        try {
            assertDiagnostic(assertThrows(SQLException.class, () -> execute(sql)), state, constraint);
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }

    static void assertDiagnostic(SQLException failure, String state, String constraint) {
        assertEquals(state, failure.getSQLState(), failure::getMessage);
        if (constraint != null) {
            var postgres = assertInstanceOf(PSQLException.class, failure);
            assertNotNull(postgres.getServerErrorMessage());
            assertEquals(constraint, postgres.getServerErrorMessage().getConstraint());
        }
    }

    void check(String table, UUID id, String name, String mutation) throws Exception {
        rejects("23514", name, "UPDATE " + table + " SET " + mutation + " WHERE id='" + id + "'");
    }

    void canonical(String table, UUID id, String name, String column, boolean optional) throws Exception {
        for (var value : new String[]{"''", "'  '", "' TEXT'", "'TEXT '", "'lower'"}) {
            check(table, id, name, column + "=" + value);
        }
        execute("UPDATE " + table + " SET " + column + "='ÁRBOL  ÁGIL' WHERE id='" + id + "'");
        if (optional) execute("UPDATE " + table + " SET " + column + "=NULL WHERE id='" + id + "'");
    }

    void index(String name, String definition) throws SQLException {
        assertEquals(definition, scalar("SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND indexname='" + name + "'"));
    }
}
