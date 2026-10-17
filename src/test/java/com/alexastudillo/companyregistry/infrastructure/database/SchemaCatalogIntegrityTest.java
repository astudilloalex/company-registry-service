package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class SchemaCatalogIntegrityTest extends SqlIntegritySupport {
    @Test
    void everyDeclaredConstraintIndexAndTriggerIsInstalledAndEnabled() throws Exception {
        var source = Files.readString(Path.of("src/main/resources/db/migration/V1__create_company_registry_schema.sql"));
        var constraints = names(source, "(?m)^    CONSTRAINT (\\w+)");
        assertEquals(constraints, rows("SELECT c.conname FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace "
                + "WHERE n.nspname='public' AND c.contype IN ('p','u','f','c') "
                + "AND c.conrelid IN (SELECT oid FROM pg_class WHERE relname LIKE 'compan%')"));
        assertEquals("0", scalar("SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace "
                + "WHERE n.nspname='public' AND NOT c.convalidated"));
        var indexes = names(source, "CREATE (?:UNIQUE )?INDEX (\\w+)");
        indexes.addAll(constraints.stream().filter(name -> name.startsWith("pk_") || name.startsWith("uq_")).toList());
        assertEquals(indexes, rows("SELECT indexname FROM pg_indexes WHERE schemaname='public' AND tablename LIKE 'compan%'"));
        assertEquals("0", scalar("SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid "
                + "WHERE c.relname LIKE 'compan%' AND (NOT i.indisvalid OR NOT i.indisready)"));
        assertEquals(names(source, "CREATE TRIGGER (\\w+)"), rows("SELECT tgname FROM pg_trigger "
                + "WHERE NOT tgisinternal AND tgrelid IN (SELECT oid FROM pg_class WHERE relname LIKE 'compan%') AND tgenabled='O'"));
        assertEquals(names(source, "CREATE FUNCTION (\\w+)"), rows("SELECT DISTINCT p.proname FROM pg_trigger t "
                + "JOIN pg_proc p ON p.oid=t.tgfoid WHERE NOT t.tgisinternal AND t.tgrelid IN (SELECT oid FROM pg_class WHERE relname LIKE 'compan%')"));
    }

    private Set<String> rows(String sql) throws Exception {
        var values = new HashSet<String>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            while (result.next()) values.add(result.getString(1));
        }
        return values;
    }

    private static Set<String> names(String source, String expression) {
        return Pattern.compile(expression).matcher(source).results().map(match -> match.group(1))
                .collect(Collectors.toCollection(HashSet::new));
    }
}
