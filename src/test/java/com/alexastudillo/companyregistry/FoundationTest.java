package com.alexastudillo.companyregistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class FoundationTest {
    @Test
    void preservesThePostgresqlMigrationAsAPackagedResource() throws Exception {
        try (var migration = getClass().getResourceAsStream(
                "/db/migration/V1__create_company_registry_schema.sql")) {
            assertNotNull(migration, "Flyway's baseline must be on the application classpath");
            var sql = new String(migration.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(6, sql.lines().filter(line -> line.startsWith("CREATE TABLE ")).count());
            assertEquals(7, sql.lines().filter(line -> line.startsWith("CREATE TYPE ")).count());
        }
    }
}
