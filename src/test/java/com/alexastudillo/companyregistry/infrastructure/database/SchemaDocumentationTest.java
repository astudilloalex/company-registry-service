package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Prevents omissions when documenting the authoritative migration; no database evidence implied. */
class SchemaDocumentationTest {
    @Test
    void diagramContainsAllSixTablesSevenEnumsAndNamedKeys() throws Exception {
        var sql = migration();
        var diagram = Files.readString(Path.of("docs/database/v1-scheme.dbml"));
        var tables = names(sql, "CREATE TABLE (\\w+)");
        var enums = names(sql, "CREATE TYPE (\\w+) AS ENUM");
        assertEquals(6, tables.size());
        assertEquals(7, enums.size());
        assertEquals(tables, names(diagram, "(?m)^Table (\\w+)"));
        assertEquals(enums, names(diagram, "(?m)^Enum (\\w+)"));
        for (var name : names(sql, "CONSTRAINT ((?:pk|uq|fk)_\\w+)|CREATE (?:UNIQUE )?INDEX (\\w+)")) {
            assertTrue(diagram.contains(name), "DBML missing " + name);
        }
    }

    @Test
    void inventoryNamesEveryConstraintIndexTriggerAndFunction() throws Exception {
        var inventory = Files.readString(Path.of("docs/database/constraint-verification.md"));
        for (var name : names(migration(), "CONSTRAINT\\s*(?:=\\s*')?(\\w+)"
                + "|CREATE (?:UNIQUE )?INDEX (\\w+)"
                + "|CREATE TRIGGER (\\w+)|CREATE FUNCTION (\\w+)")) {
            assertTrue(inventory.contains("`" + name + "`"), "Inventory missing " + name);
        }
    }

    private static String migration() throws Exception {
        var source=new StringBuilder();
        try(var files=Files.list(Path.of("src/main/resources/db/migration"))) {
            for(var file:files.filter(p->p.getFileName().toString().matches("V\\d+__.*\\.sql")).sorted().toList()) source.append(Files.readString(file)).append('\n');
        }
        return source.toString();
    }

    private static Set<String> names(String text, String expression) {
        return Pattern.compile(expression).matcher(text).results()
                .map(match -> {
                    for (int group = 1; group <= match.groupCount(); group++) {
                        if (match.group(group) != null) return match.group(group);
                    }
                    throw new IllegalStateException("Pattern must capture a name");
                }).collect(Collectors.toSet());
    }
}
