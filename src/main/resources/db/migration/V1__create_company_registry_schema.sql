
-- ============================================================
-- COMPANY REGISTRY SERVICE
-- Flyway Migration: V1__create_company_registry_schema.sql
-- Database: PostgreSQL 18+
--
-- Business aggregates:
--   1. companies
--   2. company_sites
--   3. company_site_addresses
--   4. company_site_contact_points
--
-- Technical persistence:
--   5. company_idempotency_records
--   6. company_outbox_events
--
-- Requirements:
--   - Tenant-scoped legal Party uniqueness
--   - Exactly one non-closed legal headquarters at most
--   - UUIDv7 identifiers
--   - Optimistic concurrency
--   - Canonical uppercase business text
--   - Soft deletion for addresses and contacts
--   - Transactional Outbox for RabbitMQ
--
-- Flyway owns schema migration.
-- No cross-service foreign keys.
-- No application-generated seed data.
-- ============================================================


-- ============================================================
-- 1. ENUM TYPES
-- ============================================================

CREATE TYPE company_record_status AS ENUM (
    'DRAFT',
    'ACTIVE',
    'INACTIVE',
    'ARCHIVED'
);

CREATE TYPE company_site_status AS ENUM (
    'DRAFT',
    'ACTIVE',
    'INACTIVE',
    'CLOSED'
);

CREATE TYPE company_site_type AS ENUM (
    'HEADQUARTERS',
    'BRANCH',
    'WAREHOUSE',
    'OTHER'
);

CREATE TYPE company_site_address_type AS ENUM (
    'PHYSICAL',
    'MAILING'
);

CREATE TYPE company_site_contact_type AS ENUM (
    'EMAIL',
    'PHONE',
    'MOBILE',
    'WHATSAPP'
);

CREATE TYPE company_outbox_aggregate_type AS ENUM (
    'COMPANY',
    'COMPANY_SITE'
);

CREATE TYPE outbox_status AS ENUM (
    'PENDING',
    'PUBLISHED',
    'FAILED'
);


-- ============================================================
-- 2. COMPANIES
-- ============================================================

CREATE TABLE companies (
    id uuid DEFAULT uuidv7() NOT NULL,
    tenant_id uuid NOT NULL,
    party_id uuid NOT NULL,

    code varchar(64) NOT NULL,
    operating_name varchar(200) NOT NULL,
    description varchar(500),
    logo_url varchar(1000),

    status company_record_status
        DEFAULT 'DRAFT' NOT NULL,

    version bigint DEFAULT 0 NOT NULL,

    created_at timestamptz DEFAULT now() NOT NULL,
    created_by varchar(128) NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    updated_by varchar(128) NOT NULL,

    CONSTRAINT pk_companies
        PRIMARY KEY (id),

    -- Tenant-qualified identity used by child tables.
    CONSTRAINT uq_companies_tenant_id
        UNIQUE (tenant_id, id),

    -- One legal Party can represent only one Company
    -- within the same tenant.
    CONSTRAINT uq_companies_tenant_party
        UNIQUE (tenant_id, party_id),

    -- Company business codes are tenant-scoped.
    CONSTRAINT uq_companies_tenant_code
        UNIQUE (tenant_id, code),

    CONSTRAINT ck_companies_code
        CHECK (
            btrim(code) <> ''
            AND code = btrim(code)
            AND code = upper(code)
        ),

    CONSTRAINT ck_companies_operating_name
        CHECK (
            btrim(operating_name) <> ''
            AND operating_name = btrim(operating_name)
            AND operating_name = upper(operating_name)
        ),

    CONSTRAINT ck_companies_description
        CHECK (
            description IS NULL
            OR (
                btrim(description) <> ''
                AND description = btrim(description)
                AND description = upper(description)
            )
        ),

    -- URLs are case-sensitive and must not be uppercased.
    CONSTRAINT ck_companies_logo_url
        CHECK (
            logo_url IS NULL
            OR (
                btrim(logo_url) <> ''
                AND logo_url = btrim(logo_url)
            )
        ),

    CONSTRAINT ck_companies_version
        CHECK (version >= 0),

    CONSTRAINT ck_companies_created_by
        CHECK (btrim(created_by) <> ''),

    CONSTRAINT ck_companies_updated_by
        CHECK (btrim(updated_by) <> ''),

    CONSTRAINT ck_companies_audit_dates
        CHECK (updated_at >= created_at)
);

CREATE INDEX ix_companies_tenant_status
    ON companies (tenant_id, status, id);

CREATE INDEX ix_companies_tenant_created
    ON companies (
        tenant_id,
        created_at DESC,
        id DESC
    );

COMMENT ON TABLE companies IS
    'Tenant-scoped operational Company aggregate.';

COMMENT ON COLUMN companies.party_id IS
    'Opaque reference to Party Registry. Must resolve to '
    'an existing LEGAL_ENTITY in the same tenant.';

COMMENT ON COLUMN companies.operating_name IS
    'Operational name, not the authoritative legal name.';

COMMENT ON COLUMN companies.version IS
    'Optimistic concurrency and Company aggregate version.';


-- ============================================================
-- 3. COMPANY SITES
-- ============================================================

CREATE TABLE company_sites (
    id uuid DEFAULT uuidv7() NOT NULL,
    tenant_id uuid NOT NULL,
    company_id uuid NOT NULL,

    code varchar(64) NOT NULL,
    name varchar(200) NOT NULL,

    site_type company_site_type
        DEFAULT 'BRANCH' NOT NULL,

    status company_site_status
        DEFAULT 'DRAFT' NOT NULL,

    version bigint DEFAULT 0 NOT NULL,

    created_at timestamptz DEFAULT now() NOT NULL,
    created_by varchar(128) NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    updated_by varchar(128) NOT NULL,

    CONSTRAINT pk_company_sites
        PRIMARY KEY (id),

    CONSTRAINT fk_company_sites_company
        FOREIGN KEY (tenant_id, company_id)
        REFERENCES companies (tenant_id, id)
        ON DELETE RESTRICT,

    -- Required for qualified idempotency references.
    CONSTRAINT uq_company_sites_owner_id
        UNIQUE (tenant_id, company_id, id),

    CONSTRAINT uq_company_sites_code
        UNIQUE (company_id, code),

    CONSTRAINT ck_company_sites_code
        CHECK (
            btrim(code) <> ''
            AND code = btrim(code)
            AND code = upper(code)
        ),

    CONSTRAINT ck_company_sites_name
        CHECK (
            btrim(name) <> ''
            AND name = btrim(name)
            AND name = upper(name)
        ),

    CONSTRAINT ck_company_sites_version
        CHECK (version >= 0),

    CONSTRAINT ck_company_sites_created_by
        CHECK (btrim(created_by) <> ''),

    CONSTRAINT ck_company_sites_updated_by
        CHECK (btrim(updated_by) <> ''),

    CONSTRAINT ck_company_sites_audit_dates
        CHECK (updated_at >= created_at)
);

-- Only one non-closed legal headquarters per Company.
-- Historical CLOSED headquarters remain allowed.

CREATE UNIQUE INDEX uq_company_sites_open_headquarters
    ON company_sites (company_id)
    WHERE site_type = 'HEADQUARTERS'
      AND status <> 'CLOSED';

CREATE INDEX ix_company_sites_tenant_status
    ON company_sites (
        tenant_id,
        company_id,
        status,
        id
    );

CREATE INDEX ix_company_sites_type
    ON company_sites (
        company_id,
        site_type
    );

COMMENT ON TABLE company_sites IS
    'Operational sites belonging to Companies.';

COMMENT ON COLUMN company_sites.site_type IS
    'HEADQUARTERS is the legal headquarters classification.';

COMMENT ON COLUMN company_sites.version IS
    'Optimistic concurrency version shared by Site, '
    'Address, and Contact mutations.';


-- ============================================================
-- 4. COMPANY SITE ADDRESSES
-- ============================================================

CREATE TABLE company_site_addresses (
    id uuid DEFAULT uuidv7() NOT NULL,
    site_id uuid NOT NULL,

    address_type company_site_address_type
        DEFAULT 'PHYSICAL' NOT NULL,

    country_code char(2) NOT NULL,
    administrative_division_code varchar(64),

    address_line_1 varchar(300) NOT NULL,
    address_line_2 varchar(300),
    house_number varchar(32),
    postal_code varchar(32),

    latitude numeric(9,6),
    longitude numeric(9,6),

    is_primary boolean DEFAULT false NOT NULL,

    created_at timestamptz DEFAULT now() NOT NULL,
    created_by varchar(128) NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    updated_by varchar(128) NOT NULL,

    deleted_at timestamptz,
    deleted_by varchar(128),

    CONSTRAINT pk_company_site_addresses
        PRIMARY KEY (id),

    CONSTRAINT fk_company_site_addresses_site
        FOREIGN KEY (site_id)
        REFERENCES company_sites (id)
        ON DELETE RESTRICT,

    CONSTRAINT ck_company_site_addresses_country
        CHECK (country_code ~ '^[A-Z]{2}$'),

    CONSTRAINT ck_company_site_addresses_division
        CHECK (
            administrative_division_code IS NULL
            OR (
                btrim(administrative_division_code) <> ''
                AND administrative_division_code =
                    btrim(administrative_division_code)
                AND administrative_division_code =
                    upper(administrative_division_code)
            )
        ),

    CONSTRAINT ck_company_site_addresses_line1
        CHECK (
            btrim(address_line_1) <> ''
            AND address_line_1 = btrim(address_line_1)
            AND address_line_1 = upper(address_line_1)
        ),

    CONSTRAINT ck_company_site_addresses_line2
        CHECK (
            address_line_2 IS NULL
            OR (
                btrim(address_line_2) <> ''
                AND address_line_2 = btrim(address_line_2)
                AND address_line_2 = upper(address_line_2)
            )
        ),

    CONSTRAINT ck_company_site_addresses_house_number
        CHECK (
            house_number IS NULL
            OR (
                btrim(house_number) <> ''
                AND house_number = btrim(house_number)
                AND house_number = upper(house_number)
            )
        ),

    CONSTRAINT ck_company_site_addresses_postal_code
        CHECK (
            postal_code IS NULL
            OR (
                btrim(postal_code) <> ''
                AND postal_code = btrim(postal_code)
                AND postal_code = upper(postal_code)
            )
        ),

    -- Latitude and longitude are optional,
    -- but must always be provided together.
    CONSTRAINT ck_company_site_addresses_coordinate_pair
        CHECK (
            (latitude IS NULL AND longitude IS NULL)
            OR
            (latitude IS NOT NULL AND longitude IS NOT NULL)
        ),

    CONSTRAINT ck_company_site_addresses_latitude
        CHECK (
            latitude IS NULL
            OR latitude BETWEEN -90 AND 90
        ),

    CONSTRAINT ck_company_site_addresses_longitude
        CHECK (
            longitude IS NULL
            OR longitude BETWEEN -180 AND 180
        ),

    CONSTRAINT ck_company_site_addresses_created_by
        CHECK (btrim(created_by) <> ''),

    CONSTRAINT ck_company_site_addresses_updated_by
        CHECK (btrim(updated_by) <> ''),

    CONSTRAINT ck_company_site_addresses_audit_dates
        CHECK (updated_at >= created_at),

    -- Logical deletion metadata must be consistent.
    CONSTRAINT ck_company_site_addresses_deletion
        CHECK (
            (deleted_at IS NULL AND deleted_by IS NULL)
            OR
            (
                deleted_at IS NOT NULL
                AND deleted_by IS NOT NULL
                AND btrim(deleted_by) <> ''
                AND deleted_at >= created_at
                AND is_primary = false
            )
        )
);

-- Only one current primary address per Site and type.

CREATE UNIQUE INDEX uq_company_site_primary_address
    ON company_site_addresses (
        site_id,
        address_type
    )
    WHERE is_primary = true
      AND deleted_at IS NULL;

CREATE INDEX ix_company_site_addresses_current
    ON company_site_addresses (
        site_id,
        address_type
    )
    WHERE deleted_at IS NULL;

COMMENT ON TABLE company_site_addresses IS
    'Physical and mailing addresses owned by Company Sites.';

COMMENT ON COLUMN company_site_addresses.country_code IS
    'ISO 3166-1 alpha-2 logical reference validated '
    'through Geographic Reference Service.';

COMMENT ON COLUMN
    company_site_addresses.administrative_division_code IS
    'External administrative-division reference. '
    'Must be validated against Geographic Reference Service.';


-- ============================================================
-- 5. COMPANY SITE CONTACT POINTS
-- ============================================================

CREATE TABLE company_site_contact_points (
    id uuid DEFAULT uuidv7() NOT NULL,
    site_id uuid NOT NULL,

    contact_type company_site_contact_type NOT NULL,
    value varchar(320) NOT NULL,
    label varchar(80),

    is_primary boolean DEFAULT false NOT NULL,

    created_at timestamptz DEFAULT now() NOT NULL,
    created_by varchar(128) NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    updated_by varchar(128) NOT NULL,

    deleted_at timestamptz,
    deleted_by varchar(128),

    CONSTRAINT pk_company_site_contact_points
        PRIMARY KEY (id),

    CONSTRAINT fk_company_site_contacts_site
        FOREIGN KEY (site_id)
        REFERENCES company_sites (id)
        ON DELETE RESTRICT,

    CONSTRAINT ck_company_site_contacts_value
        CHECK (
            btrim(value) <> ''
            AND value = btrim(value)
        ),

    -- Telephone-based contacts must be stored in
    -- canonical E.164 format.
    -- Full numbering validation remains in the application.
    CONSTRAINT ck_company_site_contacts_phone_format
        CHECK (
            contact_type = 'EMAIL'
            OR value ~ '^[+][1-9][0-9]{1,14}$'
        ),

    CONSTRAINT ck_company_site_contacts_label
        CHECK (
            label IS NULL
            OR (
                btrim(label) <> ''
                AND label = btrim(label)
                AND label = upper(label)
            )
        ),

    CONSTRAINT ck_company_site_contacts_created_by
        CHECK (btrim(created_by) <> ''),

    CONSTRAINT ck_company_site_contacts_updated_by
        CHECK (btrim(updated_by) <> ''),

    CONSTRAINT ck_company_site_contacts_audit_dates
        CHECK (updated_at >= created_at),

    CONSTRAINT ck_company_site_contacts_deletion
        CHECK (
            (deleted_at IS NULL AND deleted_by IS NULL)
            OR
            (
                deleted_at IS NOT NULL
                AND deleted_by IS NOT NULL
                AND btrim(deleted_by) <> ''
                AND deleted_at >= created_at
                AND is_primary = false
            )
        )
);

-- Prevent duplicate active contacts of the same type.
-- Values are compared exactly after application
-- canonicalization.

CREATE UNIQUE INDEX uq_company_site_contacts_current_value
    ON company_site_contact_points (
        site_id,
        contact_type,
        value
    )
    WHERE deleted_at IS NULL;

-- At most one current primary contact per Site and type.

CREATE UNIQUE INDEX uq_company_site_primary_contact
    ON company_site_contact_points (
        site_id,
        contact_type
    )
    WHERE is_primary = true
      AND deleted_at IS NULL;

COMMENT ON TABLE company_site_contact_points IS
    'EMAIL, PHONE, MOBILE, and WHATSAPP contact channels '
    'belonging to a Company Site.';

COMMENT ON COLUMN company_site_contact_points.value IS
    'Email case is preserved. Telephone-based values '
    'must be normalized to E.164 by the application.';


-- ============================================================
-- 6. COMPANY IDEMPOTENCY RECORDS
-- ============================================================

CREATE TABLE company_idempotency_records (
    tenant_id uuid NOT NULL,

    operation varchar(64) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,

    company_id uuid NOT NULL,
    site_id uuid,

    expected_aggregate_version bigint,

    response_http_status smallint NOT NULL,
    result_snapshot_schema_version smallint
        DEFAULT 1 NOT NULL,
    result_snapshot jsonb NOT NULL,

    created_at timestamptz DEFAULT now() NOT NULL,
    created_by varchar(128) NOT NULL,

    CONSTRAINT pk_company_idempotency_records
        PRIMARY KEY (
            tenant_id,
            operation,
            idempotency_key
        ),

    CONSTRAINT fk_company_idempotency_company
        FOREIGN KEY (tenant_id, company_id)
        REFERENCES companies (tenant_id, id)
        ON DELETE RESTRICT,

    -- site_id is nullable for Company-only operations.
    CONSTRAINT fk_company_idempotency_site
        FOREIGN KEY (
            tenant_id,
            company_id,
            site_id
        )
        REFERENCES company_sites (
            tenant_id,
            company_id,
            id
        )
        ON DELETE RESTRICT,

    CONSTRAINT ck_company_idempotency_operation
        CHECK (btrim(operation) <> ''),

    CONSTRAINT ck_company_idempotency_key
        CHECK (btrim(idempotency_key) <> ''),

    CONSTRAINT ck_company_idempotency_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),

    CONSTRAINT ck_company_idempotency_expected_version
        CHECK (
            expected_aggregate_version IS NULL
            OR expected_aggregate_version >= 0
        ),

    CONSTRAINT ck_company_idempotency_http_status
        CHECK (
            response_http_status BETWEEN 200 AND 299
        ),

    CONSTRAINT ck_company_idempotency_snapshot_version
        CHECK (result_snapshot_schema_version > 0),

    CONSTRAINT ck_company_idempotency_snapshot
        CHECK (
            jsonb_typeof(result_snapshot) = 'object'
        ),

    CONSTRAINT ck_company_idempotency_created_by
        CHECK (btrim(created_by) <> '')
);

CREATE INDEX ix_company_idempotency_owner
    ON company_idempotency_records (
        tenant_id,
        company_id,
        site_id
    );

COMMENT ON TABLE company_idempotency_records IS
    'Durable idempotency completion and original-response '
    'replay for Company and Site aggregate operations.';

COMMENT ON COLUMN company_idempotency_records.site_id IS
    'Nullable for Company operations. Required by the '
    'application for Site-scoped operations.';

COMMENT ON COLUMN company_idempotency_records.request_hash IS
    'Canonical effective request fingerprint. '
    'Generate using an approved keyed HMAC policy.';

COMMENT ON COLUMN company_idempotency_records.result_snapshot IS
    'Versioned original accepted response snapshot, '
    'including subordinate resource IDs when applicable.';


-- ============================================================
-- 7. COMPANY TRANSACTIONAL OUTBOX
-- ============================================================

CREATE TABLE company_outbox_events (
    id uuid DEFAULT uuidv7() NOT NULL,
    tenant_id uuid NOT NULL,

    aggregate_type company_outbox_aggregate_type NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL,

    event_type varchar(128) NOT NULL,
    event_schema_version smallint DEFAULT 1 NOT NULL,

    payload jsonb NOT NULL,

    occurred_at timestamptz DEFAULT now() NOT NULL,

    correlation_id varchar(128) NOT NULL,
    causation_id varchar(128),

    status outbox_status DEFAULT 'PENDING' NOT NULL,

    publish_attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamptz,
    last_attempt_at timestamptz,
    published_at timestamptz,

    last_error_code varchar(64),
    last_error_detail varchar(1000),

    created_at timestamptz DEFAULT now() NOT NULL,
    created_by varchar(128) NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    updated_by varchar(128) NOT NULL,

    version bigint DEFAULT 0 NOT NULL,

    CONSTRAINT pk_company_outbox_events
        PRIMARY KEY (id),

    CONSTRAINT uq_company_outbox_event_identity
        UNIQUE (
            tenant_id,
            aggregate_type,
            aggregate_id,
            aggregate_version,
            event_type
        ),

    CONSTRAINT ck_company_outbox_event_type
        CHECK (
            event_type IN (
                'company.created.v1',
                'company.updated.v1',
                'company.activated.v1',
                'company.deactivated.v1',
                'company.site.created.v1',
                'company.site.updated.v1',
                'company.site.closed.v1'
            )
        ),

    CONSTRAINT ck_company_outbox_aggregate_mapping
        CHECK (
            (
                aggregate_type = 'COMPANY'
                AND event_type IN (
                    'company.created.v1',
                    'company.updated.v1',
                    'company.activated.v1',
                    'company.deactivated.v1'
                )
            )
            OR
            (
                aggregate_type = 'COMPANY_SITE'
                AND event_type IN (
                    'company.site.created.v1',
                    'company.site.updated.v1',
                    'company.site.closed.v1'
                )
            )
        ),

    -- Newly registered aggregates start at version zero.
    CONSTRAINT ck_company_outbox_creation_version
        CHECK (
            (
                event_type IN (
                    'company.created.v1',
                    'company.site.created.v1'
                )
                AND aggregate_version = 0
            )
            OR
            (
                event_type NOT IN (
                    'company.created.v1',
                    'company.site.created.v1'
                )
                AND aggregate_version > 0
            )
        ),

    CONSTRAINT ck_company_outbox_schema_version
        CHECK (event_schema_version > 0),

    CONSTRAINT ck_company_outbox_payload
        CHECK (jsonb_typeof(payload) = 'object'),

    CONSTRAINT ck_company_outbox_correlation_id
        CHECK (btrim(correlation_id) <> ''),

    CONSTRAINT ck_company_outbox_causation_id
        CHECK (
            causation_id IS NULL
            OR btrim(causation_id) <> ''
        ),

    CONSTRAINT ck_company_outbox_attempts
        CHECK (publish_attempts >= 0),

    CONSTRAINT ck_company_outbox_version
        CHECK (version >= 0),

    -- Only a confirmed publication has published_at.
    CONSTRAINT ck_company_outbox_published_at
        CHECK (
            (
                status = 'PUBLISHED'
                AND published_at IS NOT NULL
            )
            OR
            (
                status <> 'PUBLISHED'
                AND published_at IS NULL
            )
        ),

    -- Terminal failures require a diagnostic code.
    CONSTRAINT ck_company_outbox_failed_error
        CHECK (
            status <> 'FAILED'
            OR (
                last_error_code IS NOT NULL
                AND btrim(last_error_code) <> ''
            )
        ),

    CONSTRAINT ck_company_outbox_created_by
        CHECK (btrim(created_by) <> ''),

    CONSTRAINT ck_company_outbox_updated_by
        CHECK (btrim(updated_by) <> ''),

    CONSTRAINT ck_company_outbox_audit_dates
        CHECK (updated_at >= created_at)
);

-- Supports scheduled polling and delivery retries.

CREATE INDEX ix_company_outbox_delivery
    ON company_outbox_events (
        status,
        next_attempt_at,
        created_at,
        id
    );

CREATE INDEX ix_company_outbox_correlation
    ON company_outbox_events (correlation_id);

COMMENT ON TABLE company_outbox_events IS
    'Transactional Outbox for asynchronous Company Registry '
    'integration events published through RabbitMQ.';

COMMENT ON COLUMN company_outbox_events.aggregate_id IS
    'Polymorphic logical reference to companies.id '
    'or company_sites.id according to aggregate_type.';

COMMENT ON COLUMN company_outbox_events.correlation_id IS
    'Required correlation identifier propagated from '
    'the trusted Process-Id request context.';

COMMENT ON COLUMN company_outbox_events.status IS
    'PENDING, PUBLISHED, or FAILED. '
    'Managed by the outbox publisher.';


-- ============================================================
-- 8. IMMUTABLE IDENTITY AND TERMINAL STATE PROTECTION
-- ============================================================

-- Company identity and original creation audit are immutable.
-- Archived Companies are terminal.

CREATE FUNCTION reject_company_immutable_changes()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.party_id IS DISTINCT FROM OLD.party_id
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.created_by IS DISTINCT FROM OLD.created_by
    THEN
        RAISE EXCEPTION 'Company identity is immutable'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_companies_immutable_identity';
    END IF;

    IF OLD.status = 'ARCHIVED'
        AND NEW IS DISTINCT FROM OLD
    THEN
        RAISE EXCEPTION 'Archived Company is immutable'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_companies_archived_terminal';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_companies_immutable_changes
BEFORE UPDATE ON companies
FOR EACH ROW
EXECUTE FUNCTION reject_company_immutable_changes();


-- Site identity and original creation audit are immutable.
-- Closed Sites are terminal.

CREATE FUNCTION reject_company_site_immutable_changes()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.company_id IS DISTINCT FROM OLD.company_id
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.created_by IS DISTINCT FROM OLD.created_by
    THEN
        RAISE EXCEPTION 'Company Site identity is immutable'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_company_sites_immutable_identity';
    END IF;

    IF OLD.status = 'CLOSED'
        AND NEW IS DISTINCT FROM OLD
    THEN
        RAISE EXCEPTION 'Closed Company Site is immutable'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_company_sites_closed_terminal';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_company_sites_immutable_changes
BEFORE UPDATE ON company_sites
FOR EACH ROW
EXECUTE FUNCTION reject_company_site_immutable_changes();


-- Addresses and contacts cannot be reassigned to another Site.
-- Original creation metadata must remain unchanged.

CREATE FUNCTION reject_company_site_child_identity_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.site_id IS DISTINCT FROM OLD.site_id
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.created_by IS DISTINCT FROM OLD.created_by
    THEN
        RAISE EXCEPTION 'Site child identity is immutable'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_company_site_child_immutable_identity';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_company_site_addresses_immutable
BEFORE UPDATE ON company_site_addresses
FOR EACH ROW
EXECUTE FUNCTION reject_company_site_child_identity_change();

CREATE TRIGGER trg_company_site_contacts_immutable
BEFORE UPDATE ON company_site_contact_points
FOR EACH ROW
EXECUTE FUNCTION reject_company_site_child_identity_change();


-- ============================================================
-- END OF V1 MIGRATION
-- ============================================================
