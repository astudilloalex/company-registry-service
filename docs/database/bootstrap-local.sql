\set ON_ERROR_STOP on
-- Run once as database administrator on a new PostgreSQL 18 database.
-- This provisions roles/grants only. Flyway alone creates application tables.
\getenv runtime_password DB_PASSWORD
\getenv migration_password FLYWAY_DB_PASSWORD
CREATE ROLE company_registry_runtime LOGIN PASSWORD :'runtime_password';
CREATE ROLE company_registry_migration LOGIN PASSWORD :'migration_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO company_registry_migration;
GRANT USAGE ON SCHEMA public TO company_registry_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE company_registry_migration IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO company_registry_runtime;
