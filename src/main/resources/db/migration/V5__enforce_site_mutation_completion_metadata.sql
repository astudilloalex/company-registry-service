-- Preserve original HU05 results and provenance; incompatible history needs explicit inspection.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM company_idempotency_records
        WHERE operation IN ('company.site.update.v1', 'company.site.activate.v1',
                            'company.site.deactivate.v1', 'company.site.close.v1')
          AND (site_id IS NULL
            OR expected_aggregate_version IS NULL OR expected_aggregate_version < 0
            OR response_http_status <> 200
            OR fingerprint_version IS NULL OR fingerprint_version <= 0
            OR fingerprint_key_id IS NULL OR fingerprint_key_id !~ '^[A-Za-z0-9_-]{1,64}$')
    ) THEN
        RAISE EXCEPTION 'HU05 completion metadata must be established before installing the Site mutation contract'
            USING ERRCODE = '23514',
                HINT = 'Inspect Site mutation acceptances; retain original snapshots and establish provenance before retrying.';
    END IF;
END;
$$;

ALTER TABLE company_idempotency_records
    ADD CONSTRAINT ck_company_idempotency_site_mutation_metadata CHECK (
        operation NOT IN ('company.site.update.v1', 'company.site.activate.v1',
                          'company.site.deactivate.v1', 'company.site.close.v1')
        OR (site_id IS NOT NULL
            AND expected_aggregate_version IS NOT NULL AND expected_aggregate_version >= 0
            AND response_http_status = 200
            AND fingerprint_version IS NOT NULL AND fingerprint_version > 0
            AND fingerprint_key_id IS NOT NULL AND fingerprint_key_id ~ '^[A-Za-z0-9_-]{1,64}$')
    );
