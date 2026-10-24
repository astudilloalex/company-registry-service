\set ON_ERROR_STOP on
-- Run connected to company_registry_service after migrations, before opening business traffic.
-- No application schema or migration history is rewritten.
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE %I.%I TO company_registry_runtime', schemaname, tablename)
FROM pg_tables WHERE schemaname = 'public' AND tablename IN
    ('companies', 'company_sites', 'company_site_addresses', 'company_site_contact_points',
     'company_idempotency_records', 'company_outbox_events') \gexec
SELECT 'REVOKE ALL ON TABLE public.flyway_schema_history FROM company_registry_runtime'
WHERE to_regclass('public.flyway_schema_history') IS NOT NULL \gexec
