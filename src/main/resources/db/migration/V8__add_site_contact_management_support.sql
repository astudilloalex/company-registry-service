-- Preserve historical contact bytes; require explicit remediation of incompatible live data.
DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1 FROM company_site_contact_points
        WHERE contact_type = 'EMAIL' AND deleted_at IS NULL
        GROUP BY site_id, lower(value COLLATE "C") HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'HU07 current EMAIL canonical collisions must be resolved before migration'
            USING ERRCODE = '23514', HINT = 'Inspect current contacts without merging or rewriting history automatically.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM company_site_contact_points
        WHERE contact_type = 'EMAIL' AND deleted_at IS NULL AND NOT (
                octet_length(value) = char_length(value)
                AND char_length(value) <= 254
                AND char_length(split_part(value, '@', 1)) <= 64
                AND value COLLATE "C" = lower(value COLLATE "C")
                AND value COLLATE "C" ~ $email$^[a-z0-9!#$%&'*+/=?^_`{|}~-]+(\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$$email$
        )
    ) THEN
        RAISE EXCEPTION 'HU07 current EMAIL values must satisfy the ASCII canonical profile before migration'
            USING ERRCODE = '23514', HINT = 'Establish explicit remediation; retain historical bytes and audit.';
    END IF;
END;
$migration$;

ALTER TABLE company_site_contact_points
    ADD CONSTRAINT ck_company_site_contacts_email_v1 CHECK (
        contact_type <> 'EMAIL' OR deleted_at IS NOT NULL OR (
                octet_length(value) = char_length(value)
                AND char_length(value) <= 254
                AND char_length(split_part(value, '@', 1)) <= 64
                AND value COLLATE "C" = lower(value COLLATE "C")
                AND value COLLATE "C" ~ $email$^[a-z0-9!#$%&'*+/=?^_`{|}~-]+(\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$$email$
        )
    );

-- Never infer provenance or rewrite previously accepted contact results.
DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1 FROM company_idempotency_records
        WHERE operation IN ('company.site.contact.create.v1', 'company.site.contact.update.v1',
                            'company.site.contact.delete.v1')
          AND (site_id IS NULL
            OR expected_aggregate_version IS NULL OR expected_aggregate_version < 0
            OR response_http_status <> CASE WHEN operation = 'company.site.contact.create.v1' THEN 201 ELSE 200 END
            OR fingerprint_version IS NULL OR fingerprint_version <= 0
            OR fingerprint_key_id IS NULL OR fingerprint_key_id !~ '^[A-Za-z0-9_-]{1,64}$')
    ) THEN
        RAISE EXCEPTION 'HU07 completion metadata must be established before installing the Site contact contract'
            USING ERRCODE = '23514',
                HINT = 'Inspect contact acceptances; retain original snapshots and establish provenance before retrying.';
    END IF;
END;
$migration$;

ALTER TABLE company_idempotency_records
    ADD CONSTRAINT ck_company_idempotency_site_contact_metadata CHECK (
        operation NOT IN ('company.site.contact.create.v1', 'company.site.contact.update.v1',
                          'company.site.contact.delete.v1')
        OR (site_id IS NOT NULL
            AND expected_aggregate_version IS NOT NULL AND expected_aggregate_version >= 0
            AND response_http_status = CASE WHEN operation = 'company.site.contact.create.v1' THEN 201 ELSE 200 END
            AND fingerprint_version IS NOT NULL AND fingerprint_version > 0
            AND fingerprint_key_id IS NOT NULL AND fingerprint_key_id ~ '^[A-Za-z0-9_-]{1,64}$')
    );

CREATE INDEX ix_company_site_contacts_page
    ON company_site_contact_points (site_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
