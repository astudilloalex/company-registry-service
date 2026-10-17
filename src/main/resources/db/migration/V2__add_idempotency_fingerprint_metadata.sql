-- Never invent fingerprint provenance for pre-HU01 accepted results.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM company_idempotency_records WHERE operation = 'company.create.v1') THEN
        RAISE EXCEPTION 'HU01 completion provenance must be established before installing fingerprint metadata'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

ALTER TABLE company_idempotency_records
    ADD COLUMN fingerprint_version smallint,
    ADD COLUMN fingerprint_key_id varchar(64),
    ADD CONSTRAINT ck_company_idempotency_fingerprint_metadata CHECK (
        (fingerprint_version IS NULL AND fingerprint_key_id IS NULL)
        OR (fingerprint_version IS NOT NULL AND fingerprint_version > 0
            AND fingerprint_key_id IS NOT NULL AND fingerprint_key_id ~ '^[A-Za-z0-9_-]{1,64}$')
    ),
    ADD CONSTRAINT ck_company_idempotency_creation_metadata CHECK (
        operation <> 'company.create.v1'
        OR (fingerprint_version IS NOT NULL AND fingerprint_key_id IS NOT NULL
            AND response_http_status = 201 AND site_id IS NULL AND expected_aggregate_version IS NULL)
    );

COMMENT ON COLUMN company_idempotency_records.fingerprint_version IS
    'Immutable canonicalization and fingerprint policy used for this acceptance. Null for historical technical namespaces.';
COMMENT ON COLUMN company_idempotency_records.fingerprint_key_id IS
    'External HMAC key identity, never secret material. Retain the historical key while any acceptance depends on it.';
