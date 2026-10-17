package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;

/** JDBC is used only to test the SQL contract, never in operational adapters. */
@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class CompanyIntegrityTest {
    @Inject
    Flyway flyway;

    private Connection connection;
    private UUID company;
    private UUID tenant;
    private UUID party;

    @BeforeEach
    void fixture() throws Exception {
        connection = flyway.getConfiguration().getDataSource().getConnection();
        connection.setAutoCommit(false);
        tenant = UUID.randomUUID();
        party = UUID.randomUUID();
        company = insert(tenant, party, "COMPANY");
    }

    @AfterEach
    void rollback() throws Exception {
        if (connection != null) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    @Test
    void defaultsCanonicalTextAndOpaqueValuesRoundTrip() throws Exception {
        assertEquals(7, company.version());
        execute("UPDATE companies SET operating_name='COMPAÑÍA  ÁGIL', description='DESCRIPCIÓN', "
                + "logo_url='https://host/LogoAb.png', version=1, updated_by='Actor-aB' WHERE id='" + company + "'");
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT * FROM companies WHERE id='" + company + "'")) {
            assertTrue(row.next());
            assertEquals("DRAFT", row.getString("status"));
            assertEquals("COMPAÑÍA  ÁGIL", row.getString("operating_name"));
            assertEquals("DESCRIPCIÓN", row.getString("description"));
            assertEquals("https://host/LogoAb.png", row.getString("logo_url"));
            assertEquals("Actor-aB", row.getString("updated_by"));
            assertEquals(1, row.getLong("version"));
            assertFalse(row.getTimestamp("updated_at").before(row.getTimestamp("created_at")));
            assertFalse(row.next());
        }
    }

    @Test
    void primaryKeyAndTenantQualifiedUniqueness() throws Exception {
        rejects("23505", "pk_companies", "INSERT INTO companies "
                + "(id,tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES ('"
                + company + "',uuidv7(),uuidv7(),'OTHER','OTHER','test','test')");
        rejectsInsert("uq_companies_tenant_party", tenant, party, "OTHER");
        rejectsInsert("uq_companies_tenant_code", tenant, UUID.randomUUID(), "COMPANY");
        insert(UUID.randomUUID(), party, "COMPANY");
        insert(tenant, UUID.randomUUID(), "DISTINCT");
        assertEquals("UNIQUE (tenant_id, id)", scalar("SELECT pg_get_constraintdef(oid) "
                + "FROM pg_constraint WHERE conname='uq_companies_tenant_id'"));
        assertEquals("PRIMARY KEY (id)", scalar("SELECT pg_get_constraintdef(oid) "
                + "FROM pg_constraint WHERE conname='pk_companies'"));
    }

    @Test
    void archivedPartyAndCodeStayReservedAndStateIsTerminal() throws Exception {
        execute("UPDATE companies SET status='ARCHIVED' WHERE id='" + company + "'");
        execute("UPDATE companies SET status='ARCHIVED' WHERE id='" + company + "'");
        rejectsInsert("uq_companies_tenant_party", tenant, party, "OTHER");
        rejectsInsert("uq_companies_tenant_code", tenant, UUID.randomUUID(), "COMPANY");
        for (var mutation : new String[]{"status='DRAFT'", "status='ACTIVE'", "status='INACTIVE'",
                "description='CHANGED'", "version=version+1", "updated_by='other'"}) {
            rejectsUpdate("23514", "ck_companies_archived_terminal", mutation);
        }
    }

    @ParameterizedTest(name = "immutable {0}")
    @MethodSource("identityMutations")
    void identityAndOriginalAuditAreImmutable(String mutation) throws Exception {
        rejectsUpdate("23514", "ck_companies_immutable_identity", mutation);
    }

    static Stream<String> identityMutations() {
        return Stream.of("id=uuidv7()", "tenant_id=uuidv7()", "party_id=uuidv7()",
                "created_at=created_at-interval '1 second'", "created_by='another-actor'");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("invalidValues")
    void invalidFieldsHaveNamedCheckFailures(String constraint, String mutation) throws Exception {
        rejectsUpdate("23514", constraint, mutation);
    }

    static Stream<Arguments> invalidValues() {
        var canonical = Stream.of("code", "operating_name", "description")
                .flatMap(column -> Stream.of("''", "'  '", "' LOWER'", "'LOWER '", "'lower'")
                        .map(value -> Arguments.of("ck_companies_" + column, column + "=" + value)));
        return Stream.concat(canonical, Stream.of(
                Arguments.of("ck_companies_logo_url", "logo_url=''"),
                Arguments.of("ck_companies_logo_url", "logo_url='  '"),
                Arguments.of("ck_companies_logo_url", "logo_url=' https://host/LogoAb.png'"),
                Arguments.of("ck_companies_logo_url", "logo_url='https://host/LogoAb.png '"),
                Arguments.of("ck_companies_version", "version=-1"),
                Arguments.of("ck_companies_updated_by", "updated_by='  '"),
                Arguments.of("ck_companies_audit_dates", "updated_at=created_at-interval '1 second'")));
    }

    @Test
    void creationActorCheckIsExercisedOnInsertBeforeIdentityTrigger() throws Exception {
        rejects("23514", "ck_companies_created_by", "INSERT INTO companies "
                + "(tenant_id,party_id,code,operating_name,created_by,updated_by) "
                + "VALUES(uuidv7(),uuidv7(),'NEW','NEW','  ','test')");
    }

    @Test
    void supportedStatesAndInitialVersion() throws Exception {
        assertEquals("0", scalar("SELECT version::text FROM companies WHERE id='" + company + "'"));
        assertEquals("DRAFT,ACTIVE,INACTIVE,ARCHIVED", scalar("SELECT string_agg(enumlabel,',' ORDER BY enumsortorder) "
                + "FROM pg_enum WHERE enumtypid='company_record_status'::regtype"));
        for (var state : new String[]{"ACTIVE", "INACTIVE", "ACTIVE", "ARCHIVED"}) {
            execute("UPDATE companies SET status='" + state + "' WHERE id='" + company + "'");
        }
        rejectsUpdate("22P02", null, "status='UNKNOWN'");
    }

    @Test
    void requiredColumnsAndLengthsAreEnforced() throws Exception {
        for (var column : new String[]{"code", "operating_name", "status", "version", "updated_at", "updated_by"}) {
            rejectsUpdate("23502", null, column + "=NULL");
        }
        for (var value : new String[][]{{"code", "65"}, {"operating_name", "201"},
                {"description", "501"}, {"logo_url", "1001"}, {"updated_by", "129"}}) {
            rejectsUpdate("22001", null, value[0] + "=repeat('A'," + value[1] + ")");
        }
        // Protected fields must be checked on INSERT, not UPDATE (the identity trigger runs first).
        for (var column : new String[]{"id", "tenant_id", "party_id", "created_at", "created_by"}) {
            var columns = "id,tenant_id,party_id,code,operating_name,created_at,created_by,updated_by";
            var values = new java.util.LinkedHashMap<String, String>();
            values.put("id", "uuidv7()"); values.put("tenant_id", "uuidv7()"); values.put("party_id", "uuidv7()");
            values.put("code", "'NEW'"); values.put("operating_name", "'NEW'"); values.put("created_at", "now()");
            values.put("created_by", "'test'"); values.put("updated_by", "'test'");
            values.put(column, "NULL");
            rejects("23502", null, "INSERT INTO companies (" + columns + ") VALUES ("
                    + String.join(",", values.values()) + ")");
        }
    }

    @Test
    void queryIndexesAndEnabledTriggerRetainTheirDefinitions() throws Exception {
        assertEquals("CREATE INDEX ix_companies_tenant_status ON public.companies USING btree (tenant_id, status, id)",
                scalar("SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND indexname='ix_companies_tenant_status'"));
        assertEquals("CREATE INDEX ix_companies_tenant_created ON public.companies USING btree (tenant_id, created_at DESC, id DESC)",
                scalar("SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND indexname='ix_companies_tenant_created'"));
        assertEquals("O:reject_company_immutable_changes", scalar("SELECT t.tgenabled::text || ':' || p.proname "
                + "FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid "
                + "WHERE t.tgrelid='companies'::regclass AND t.tgname='trg_companies_immutable_changes'"));
    }

    private UUID insert(UUID owner, UUID legalParty, String code) throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO companies "
                + "(tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES(?,?,?,'COMPANY','test','test') RETURNING id")) {
            statement.setObject(1, owner);
            statement.setObject(2, legalParty);
            statement.setString(3, code);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getObject(1, UUID.class);
            }
        }
    }

    private void rejectsInsert(String constraint, UUID owner, UUID legalParty, String code) throws Exception {
        rejects("23505", constraint, "INSERT INTO companies "
                + "(tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES('"
                + owner + "','" + legalParty + "','" + code + "','COMPANY','test','test')");
    }

    private void rejectsUpdate(String state, String constraint, String mutation) throws Exception {
        rejects(state, constraint, "UPDATE companies SET " + mutation + " WHERE id='" + company + "'");
    }

    private void rejects(String state, String constraint, String sql) throws Exception {
        var savepoint = connection.setSavepoint();
        try {
            var failure = assertThrows(SQLException.class, () -> execute(sql));
            assertEquals(state, failure.getSQLState(), sql);
            if (constraint != null) {
                var postgresFailure = assertInstanceOf(PSQLException.class, failure);
                assertNotNull(postgresFailure.getServerErrorMessage());
                assertEquals(constraint, postgresFailure.getServerErrorMessage().getConstraint());
            }
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(sql));
        }
    }

    private String scalar(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            var value = row.getString(1);
            assertFalse(row.next());
            return value;
        }
    }
}
