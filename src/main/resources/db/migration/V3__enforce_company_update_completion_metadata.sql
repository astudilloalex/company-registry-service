-- Diagnose unknown historical update provenance rather than fabricating it.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM company_idempotency_records
        WHERE operation = 'company.update.v1'
          AND (fingerprint_version IS NULL OR fingerprint_version <= 0
            OR fingerprint_key_id IS NULL OR fingerprint_key_id !~ '^[A-Za-z0-9_-]{1,64}$'
            OR expected_aggregate_version IS NULL OR expected_aggregate_version < 0
            OR response_http_status <> 200 OR site_id IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'HU02 completion metadata must be established before installing the update contract'
            USING ERRCODE = '23514',
                HINT = 'Inspect company.update.v1 acceptances; retain original snapshots and establish provenance before retrying.';
    END IF;
END;
$$;

ALTER TABLE company_idempotency_records
    ADD CONSTRAINT ck_company_idempotency_update_metadata CHECK (
        operation <> 'company.update.v1'
        OR (fingerprint_version IS NOT NULL AND fingerprint_version > 0
            AND fingerprint_key_id IS NOT NULL AND fingerprint_key_id ~ '^[A-Za-z0-9_-]{1,64}$'
            AND expected_aggregate_version IS NOT NULL AND expected_aggregate_version >= 0
            AND response_http_status = 200 AND site_id IS NULL)
    );
