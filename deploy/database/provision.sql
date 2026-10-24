\set ON_ERROR_STOP on
-- Execute as the existing PostgreSQL administrator, initially connected to postgres.
-- Passwords are inherited from the administrator's protected environment, never CLI literals.
\getenv runtime_password DB_PASSWORD
\getenv migration_password FLYWAY_DB_PASSWORD
SELECT format('CREATE ROLE company_registry_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'runtime_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'company_registry_runtime') \gexec
SELECT format('CREATE ROLE company_registry_migration LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'migration_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'company_registry_migration') \gexec
SELECT 'CREATE DATABASE company_registry_service OWNER company_registry_migration'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'company_registry_service') \gexec

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname IN ('company_registry_runtime', 'company_registry_migration')
               AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR NOT rolcanlogin))
       OR EXISTS (SELECT FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member
                  WHERE r.rolname IN ('company_registry_runtime', 'company_registry_migration')) THEN
        RAISE EXCEPTION 'Company database roles must not have administrative privileges or role memberships';
    END IF;
END $$;
ALTER DATABASE company_registry_service OWNER TO company_registry_migration;
REVOKE ALL ON DATABASE company_registry_service FROM PUBLIC;
GRANT CONNECT ON DATABASE company_registry_service TO company_registry_runtime;
\connect company_registry_service
ALTER SCHEMA public OWNER TO company_registry_migration;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO company_registry_runtime;
-- Flyway owns DDL. Runtime receives application-table DML on creation.
ALTER DEFAULT PRIVILEGES FOR ROLE company_registry_migration IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO company_registry_runtime;
-- Existing tables are handled too; running this again does not rotate passwords.
\ir grant-runtime.sql
