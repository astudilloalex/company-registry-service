-- Run once as a database administrator connected to "postgres", outside a transaction.
-- The Podman command in README.md already creates this database via POSTGRES_DB.
-- After creation, run bootstrap-local.sql connected to company_registry_service.
CREATE DATABASE company_registry_service;
