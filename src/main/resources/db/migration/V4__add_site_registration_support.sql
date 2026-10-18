-- Preserve historical acceptance data; never manufacture missing HU04 provenance.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM company_idempotency_records
        WHERE operation = 'company.site.create.v1'
          AND (site_id IS NULL OR expected_aggregate_version IS NOT NULL
            OR response_http_status <> 201
            OR fingerprint_version IS NULL OR fingerprint_version <= 0
            OR fingerprint_key_id IS NULL OR fingerprint_key_id !~ '^[A-Za-z0-9_-]{1,64}$')
    ) THEN
        RAISE EXCEPTION 'HU04 completion metadata must be established before installing the Site registration contract'
            USING ERRCODE = '23514',
                HINT = 'Inspect company.site.create.v1 acceptances; retain original snapshots and establish provenance before retrying.';
    END IF;
END;
$$;

ALTER TABLE company_idempotency_records
    ADD CONSTRAINT ck_company_idempotency_site_creation_metadata CHECK (
        operation <> 'company.site.create.v1'
        OR (site_id IS NOT NULL AND expected_aggregate_version IS NULL
            AND response_http_status = 201
            AND fingerprint_version IS NOT NULL AND fingerprint_version > 0
            AND fingerprint_key_id IS NOT NULL AND fingerprint_key_id ~ '^[A-Za-z0-9_-]{1,64}$')
    );

CREATE INDEX ix_company_sites_tenant_company_created
    ON company_sites (tenant_id, company_id, created_at DESC, id DESC);
