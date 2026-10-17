package com.alexastudillo.companyregistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import java.security.MessageDigest;
import java.util.HexFormat;

class FoundationTest {
    @Test
    void preservesThePostgresqlMigrationAsAPackagedResource() throws Exception {
        try (var migration = getClass().getResourceAsStream(
                "/db/migration/V1__create_company_registry_schema.sql")) {
            assertNotNull(migration, "Flyway's baseline must be on the application classpath");
            var bytes = migration.readAllBytes();
            assertEquals("166a953bc45dd2f758ec2b11a92d4625a419d46123e2d6b32826b0b6ab5e4904",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    "V1 is immutable; use a later migration for demonstrated defects");
            var sql = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(6, sql.lines().filter(line -> line.startsWith("CREATE TABLE ")).count());
            assertEquals(7, sql.lines().filter(line -> line.startsWith("CREATE TYPE ")).count());
        }
    }
}
