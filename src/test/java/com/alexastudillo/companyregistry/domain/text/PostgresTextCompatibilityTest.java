package com.alexastudillo.companyregistry.domain.text;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PostgresTextCompatibilityTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Test
    void canonicalJavaTextSatisfiesPostgresqlUppercaseAndTrimChecks() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var query = connection.prepareStatement("SELECT ? = upper(?), ? = btrim(?), btrim(?) <> ''")) {
            for (var input : new String[] {"  compañía  ágil  ", "straße", "izmir", "áéíóú ñ ç", "sede norte"}) {
                var canonical = BusinessText.required(input, 200);
                for (int index = 1; index <= 5; index++) {
                    query.setString(index, canonical);
                }
                try (var result = query.executeQuery()) {
                    assertTrue(result.next());
                    assertTrue(result.getBoolean(1), "PostgreSQL upper rejects: " + canonical);
                    assertTrue(result.getBoolean(2), "PostgreSQL btrim rejects: " + canonical);
                    assertTrue(result.getBoolean(3));
                }
            }
        }
    }
}
