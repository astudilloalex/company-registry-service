-- Never infer provenance or rewrite previously accepted address results.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM company_idempotency_records
        WHERE operation IN ('company.site.address.create.v1', 'company.site.address.update.v1',
                            'company.site.address.delete.v1')
          AND (site_id IS NULL
            OR expected_aggregate_version IS NULL OR expected_aggregate_version < 0
            OR response_http_status <> CASE WHEN operation = 'company.site.address.create.v1' THEN 201 ELSE 200 END
            OR fingerprint_version IS NULL OR fingerprint_version <= 0
            OR fingerprint_key_id IS NULL OR fingerprint_key_id !~ '^[A-Za-z0-9_-]{1,64}$')
    ) THEN
        RAISE EXCEPTION 'HU06 completion metadata must be established before installing the Site address contract'
            USING ERRCODE = '23514',
                HINT = 'Inspect address acceptances; retain original snapshots and establish provenance before retrying.';
    END IF;
END;
$$;

ALTER TABLE company_idempotency_records
    ADD CONSTRAINT ck_company_idempotency_site_address_metadata CHECK (
        operation NOT IN ('company.site.address.create.v1', 'company.site.address.update.v1',
                          'company.site.address.delete.v1')
        OR (site_id IS NOT NULL
            AND expected_aggregate_version IS NOT NULL AND expected_aggregate_version >= 0
            AND response_http_status = CASE WHEN operation = 'company.site.address.create.v1' THEN 201 ELSE 200 END
            AND fingerprint_version IS NOT NULL AND fingerprint_version > 0
            AND fingerprint_key_id IS NOT NULL AND fingerprint_key_id ~ '^[A-Za-z0-9_-]{1,64}$')
    );

CREATE INDEX ix_company_site_addresses_page
    ON company_site_addresses (site_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
