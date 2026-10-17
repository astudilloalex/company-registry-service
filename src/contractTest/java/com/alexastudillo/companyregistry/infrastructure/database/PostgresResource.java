package com.alexastudillo.companyregistry.infrastructure.database;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Shared external database provisioning; no dependency on unit-test CDI outputs
 * or process-local mocks.
 */
public class PostgresResource implements QuarkusTestResourceLifecycleManager {
    private PostgreSQLContainer postgres;

    @Override
    public Map<String, String> start() {
        postgres = new PostgreSQLContainer("postgres:18-alpine");
        postgres = postgres.withEnv("DB_PASSWORD", "runtime_test");
        postgres = postgres.withEnv("FLYWAY_DB_PASSWORD", "migration_test");
        postgres = postgres.withCopyFileToContainer(MountableFile.forHostPath("docs/database/bootstrap-local.sql"),
                "/tmp/bootstrap-local.sql");
        postgres.start();
        try {
            var bootstrap = postgres.execInContainer("psql", "-U", postgres.getUsername(),
                    "-d", postgres.getDatabaseName(), "-f", "/tmp/bootstrap-local.sql");
            if (bootstrap.getExitCode() != 0) {
                throw new IllegalStateException("Local bootstrap failed: " + bootstrap.getStderr());
            }
        } catch (Exception failure) {
            postgres.stop();
            throw new IllegalStateException("Cannot provision isolated database", failure);
        }
        return Map.of(
                "quarkus.datasource.username", "company_registry_runtime",
                "quarkus.datasource.password", "runtime_test",
                "quarkus.datasource.jdbc.url", postgres.getJdbcUrl(),
                "quarkus.datasource.reactive.url", postgres.getJdbcUrl().substring("jdbc:".length()),
                "quarkus.flyway.username", "company_registry_migration",
                "quarkus.flyway.password", "migration_test");
    }

    @Override
    public void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }
}
