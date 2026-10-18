-- Preserve accepted results and provenance; incompatible HU03 history requires inspection.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM company_idempotency_records
        WHERE operation IN ('company.activate.v1', 'company.deactivate.v1', 'company.archive.v1')
          AND (site_id IS NOT NULL
            OR expected_aggregate_version IS NULL OR expected_aggregate_version < 0
            OR response_http_status <> 200
            OR fingerprint_version IS NULL OR fingerprint_version <= 0
            OR fingerprint_key_id IS NULL OR fingerprint_key_id !~ '^[A-Za-z0-9_-]{1,64}$')
    ) THEN
        RAISE EXCEPTION 'HU03 completion metadata must be established before installing the Company lifecycle contract'
            USING ERRCODE = '23514',
                HINT = 'Inspect Company lifecycle acceptances; retain original snapshots and establish provenance before retrying.';
    END IF;
END;
$$;

ALTER TABLE company_idempotency_records
    ADD CONSTRAINT ck_company_idempotency_lifecycle_metadata CHECK (
        operation NOT IN ('company.activate.v1', 'company.deactivate.v1', 'company.archive.v1')
        OR (site_id IS NULL
            AND expected_aggregate_version IS NOT NULL AND expected_aggregate_version >= 0
            AND response_http_status = 200
            AND fingerprint_version IS NOT NULL AND fingerprint_version > 0
            AND fingerprint_key_id IS NOT NULL AND fingerprint_key_id ~ '^[A-Za-z0-9_-]{1,64}$')
    );
