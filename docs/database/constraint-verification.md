# V1 constraint verification inventory

Authority: `src/main/resources/db/migration/V1__create_company_registry_schema.sql`.
Diagram: [v1-scheme.dbml](v1-scheme.dbml). V1 remains unchanged.

## Evidence convention

The cases below are the verification plan for tasks 5.2–5.7; only the suites
explicitly recorded below have executed PostgreSQL evidence. Each row names an independent catalog object; execution
evidence and concrete test methods must be added as the corresponding tasks pass.
`DatabaseStartupTest` already covers installation/history and runtime privileges;
`PostgresTextCompatibilityTest` covers Java/PostgreSQL accented-text compatibility.
Neither establishes exhaustive constraint coverage.

Documentation coverage: `SchemaDocumentationTest` passed with
`./gradlew test --tests '*SchemaDocumentationTest'`: six table names, seven enum
names, all named keys/indexes in DBML, and all constraints/indexes/triggers/functions
in this inventory. This static check does not substitute for PostgreSQL DML tests.

All valid fixtures use distinct UUIDs, canonical business text, nonblank actors and
ordered audit timestamps unless the case explicitly changes one of these values.
Negative cases must assert SQLSTATE and constraint name, not merely any exception.
Use PostgreSQL 18, rollback failed statements with savepoints or separate transactions,
and verify accepted rows retain their original values. NOT NULL, field lengths,
enum rejection and UUIDv7 defaults are additional type/column checks for every table.

## Company — verified suite `CompanyIntegrityTest`

Executed against isolated PostgreSQL 18 via Podman/Testcontainers: **34 cases passed**.
Command: `DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew test --tests '*CompanyIntegrityTest' --tests '*SchemaDocumentationTest'`.
Concrete method → constraints (parameterized methods assert the named server diagnostic):

- `defaultsCanonicalTextAndOpaqueValuesRoundTrip`: UUIDv7, initial state, canonical
  accented text, opaque URL/actor, positive version and coherent audit.
- `primaryKeyAndTenantQualifiedUniqueness`: `pk_companies`,
  `uq_companies_tenant_id`, `uq_companies_tenant_party`, `uq_companies_tenant_code`.
- `invalidFieldsHaveNamedCheckFailures`: `ck_companies_code`,
  `ck_companies_operating_name`, `ck_companies_description`, `ck_companies_logo_url`,
  `ck_companies_version`, `ck_companies_updated_by`, `ck_companies_audit_dates`.
- `creationActorCheckIsExercisedOnInsertBeforeIdentityTrigger`: `ck_companies_created_by`.
- `identityAndOriginalAuditAreImmutable`: `ck_companies_immutable_identity`, all
  five protected fields through `trg_companies_immutable_changes`.
- `archivedPartyAndCodeStayReservedAndStateIsTerminal`:
  `ck_companies_archived_terminal`, Party/code uniqueness after archival, identical
  update accepted and effective mutations rejected.
- `supportedStatesAndInitialVersion`: enum values, invalid enum and initial version 0.
- `requiredColumnsAndLengthsAreEnforced`: all required columns and mutable varchar limits.
- `queryIndexesAndEnabledTriggerRetainTheirDefinitions`: both Company index definitions
  and enabled binding to `reject_company_immutable_changes`.

| Object | Valid case | Invalid case |
| --- | --- | --- |
| `pk_companies` | Distinct IDs | Duplicate id |
| `uq_companies_tenant_id` | Distinct tenant/id pairs | Duplicate pair (also violates PK; assert catalog definition independently) |
| `uq_companies_tenant_party` | Same Party in different tenants | Same tenant/Party, including ARCHIVED |
| `uq_companies_tenant_code` | Same code in different tenants | Same tenant/code |
| `ck_companies_code` | `COMPAÑÍA` | Blank, exterior spaces or lowercase |
| `ck_companies_operating_name` | `COMPAÑÍA  ÁGIL` | Blank, untrimmed or lowercase |
| `ck_companies_description` | NULL or `DESCRIPCIÓN` | Blank, untrimmed or lowercase |
| `ck_companies_logo_url` | NULL or `https://host/LogoAb.png` | Blank or untrimmed |
| `ck_companies_version` | 0 and positive | -1 |
| `ck_companies_created_by` | Nonblank case-preserved actor | Blank |
| `ck_companies_updated_by` | Nonblank actor | Blank |
| `ck_companies_audit_dates` | Equal or later update | Update before creation |
| `trg_companies_immutable_changes` | Mutable name or identical update | Each protected field mutation |
| `ck_companies_immutable_identity` (trigger diagnostic) | Original id/tenant/Party/creation | Change each of id, tenant_id, party_id, created_at, created_by |
| `ck_companies_archived_terminal` (trigger diagnostic) | Identical ARCHIVED update | Change any nonidentity field after ARCHIVED |
| `ix_companies_tenant_status` | Catalog keys `(tenant_id,status,id)` | Missing/reordered keys detected by catalog assertion |
| `ix_companies_tenant_created` | Catalog `(tenant_id,created_at DESC,id DESC)` | Missing/reversed sort detected |

## Site — verified suite `SiteIntegrityTest`

Executed with `./gradlew test --tests '*SiteIntegrityTest'` using the same Podman
environment as Company: **6 tests passed**, including an observed unique-key lock
between two independent transactions before the winning commit.

- `identityKeysAndQualifiedOwnership`: PK, owner UNIQUE catalog, scoped code,
  tenant-qualified FK, unknown owner, cross-tenant rejection and RESTRICT delete.
- `textVersionAndAuditChecks`: every Site text/version/audit CHECK listed below.
- `immutableIdentityAndClosedTerminal`: all protected fields, terminal-state
  diagnostic, and identical CLOSED update accepted.
- `headquartersPredicateAllowsClosedHistoryAndOneCurrentOfAnyState`: all three
  non-CLOSED states, multiple historical CLOSED rows, other Site types.
- `concurrentHeadquartersHaveExactlyOneCommit`: real blocked competing INSERT,
  exactly one committed row, losing SQLSTATE 23505 and exact index diagnostic.
- `enumsIndexesAndTriggerDefinitions`: enum values/rejection, index keys and HQ
  predicate, enabled trigger/function binding. PostgreSQL 18 reports RESTRICT
  deletes with SQLSTATE 23001 (invalid references use 23503).

| Object | Valid case | Invalid case |
| --- | --- | --- |
| `pk_company_sites` | Distinct IDs | Duplicate id |
| `fk_company_sites_company` | Matching tenant/Company | Unknown Company, other tenant, delete referenced Company |
| `uq_company_sites_owner_id` | Distinct owner/id | Duplicate tuple (PK overlaps; catalog assertion required) |
| `uq_company_sites_code` | Same code in different Companies | Same Company/code |
| `ck_company_sites_code` | Canonical code | Blank, untrimmed or lowercase |
| `ck_company_sites_name` | Canonical accented name | Blank, untrimmed or lowercase |
| `ck_company_sites_version` | 0 and positive | -1 |
| `ck_company_sites_created_by` | Nonblank actor | Blank |
| `ck_company_sites_updated_by` | Nonblank actor | Blank |
| `ck_company_sites_audit_dates` | Equal/later update | Earlier update |
| `uq_company_sites_open_headquarters` | One DRAFT/ACTIVE/INACTIVE HQ; multiple CLOSED historical HQ | Second non-CLOSED HQ; two independent concurrent transactions: exactly one commit |
| `trg_company_sites_immutable_changes` | Mutable name/identical update | Protected identity mutation |
| `ck_company_sites_immutable_identity` (trigger diagnostic) | Original identity/creation | Change each of id, tenant_id, company_id, created_at, created_by |
| `ck_company_sites_closed_terminal` (trigger diagnostic) | Identical CLOSED update | Any effective nonidentity mutation after CLOSED |
| `ix_company_sites_tenant_status` | Keys `(tenant_id,company_id,status,id)` | Missing/reordered keys detected |
| `ix_company_sites_type` | Keys `(company_id,site_type)` | Missing/reordered keys detected |

## Address — verified suite `AddressIntegrityTest`

`./gradlew test --tests '*AddressIntegrityTest'` passed **5 tests** against
PostgreSQL 18 with the same Podman environment:

- `keysOwnershipDefaultsAndTypes`: PK, FK/RESTRICT, UUIDv7/defaults, enum,
  required-column rejection.
- `everyTextAndAuditCheckHasPositiveAndNegativeCases`: country, division, both
  lines, house/postal code and all actor/audit CHECKs.
- `coordinatePairsAndInclusiveBounds`: nullable pair, both missing-part failures,
  inclusive extrema, six-decimal round-trip and both out-of-range sides.
- `partialPrimaryAndCoherentDeletionKeepHistory`: all deletion CHECK branches,
  per-type partial uniqueness, history/replacement and exact index definitions.
- `childIdentityCannotBeReassigned`: every immutable field and enabled binding
  of `trg_company_site_addresses_immutable` to the shared trigger function.

| Object | Valid case | Invalid case |
| --- | --- | --- |
| `pk_company_site_addresses` | Distinct IDs | Duplicate id |
| `fk_company_site_addresses_site` | Existing Site | Unknown Site, delete referenced Site |
| `ck_company_site_addresses_country` | `EC` | Lowercase, nonletters, wrong length |
| `ck_company_site_addresses_division` | NULL or canonical division | Blank, untrimmed or lowercase |
| `ck_company_site_addresses_line1` | Canonical accented line | Blank, untrimmed or lowercase |
| `ck_company_site_addresses_line2` | NULL or canonical line | Blank, untrimmed or lowercase |
| `ck_company_site_addresses_house_number` | NULL or canonical number | Blank, untrimmed or lowercase |
| `ck_company_site_addresses_postal_code` | NULL or canonical code | Blank, untrimmed or lowercase |
| `ck_company_site_addresses_coordinate_pair` | Both NULL or both populated | Only latitude or only longitude |
| `ck_company_site_addresses_latitude` | -90, 90 and internal values | Below -90 / above 90 |
| `ck_company_site_addresses_longitude` | -180, 180 and internal values | Below -180 / above 180 |
| `ck_company_site_addresses_created_by` | Nonblank actor | Blank |
| `ck_company_site_addresses_updated_by` | Nonblank actor | Blank |
| `ck_company_site_addresses_audit_dates` | Equal/later update | Earlier update |
| `ck_company_site_addresses_deletion` | Both NULL; or date>=creation, actor, not primary | Missing actor/date, blank actor, earlier date, deleted primary |
| `uq_company_site_primary_address` | One current primary per Site/type; deleted nonprimary history | Second current primary same Site/type |
| `ix_company_site_addresses_current` | Keys Site/type and predicate `deleted_at IS NULL` | Missing keys/predicate detected |
| `trg_company_site_addresses_immutable` | Mutable address text | Protected field mutation |
| `ck_company_site_child_immutable_identity` (shared trigger diagnostic) | Original id/Site/creation | Change each of id, site_id, created_at, created_by |

## Contact — verified suite `ContactIntegrityTest`

`./gradlew test --tests '*ContactIntegrityTest'`: **5 tests passed** on PostgreSQL
18 with the same Podman environment.

- `keysOwnerTypesAndEmailPreservation`: PK, FK/RESTRICT, UUIDv7, enum/rejection,
  exact email round-trip.
- `everyValueLabelAndAuditCheck`: all value, label and actor/audit CHECKs below.
- `allTelephoneTypesRequireE164ButEmailIsNotUppercased`: all three phone types,
  accepted SQL format boundaries and rejected malformed values; case-distinct
  emails remain distinct. Full numbering/email validation belongs to Domain.
- `currentUniquenessPrimaryAndDeletionPreserveHistory`: exact-value and primary
  partial indexes, all deletion branches, historical value reuse and index definitions.
- `originalIdentityIsImmutable`: four protected fields and enabled shared function binding.

| Object | Valid case | Invalid case |
| --- | --- | --- |
| `pk_company_site_contact_points` | Distinct IDs | Duplicate id |
| `fk_company_site_contacts_site` | Existing Site | Unknown Site, delete referenced Site |
| `ck_company_site_contacts_value` | `Mi.Usuario@example.com` preserved | Blank or untrimmed |
| `ck_company_site_contacts_phone_format` | EMAIL unchanged; PHONE/MOBILE/WHATSAPP `+593999999999` | Missing +, zero prefix, nonnumeric, <2 or >15 digits for each phone type |
| `ck_company_site_contacts_label` | NULL or canonical label | Blank, untrimmed or lowercase |
| `ck_company_site_contacts_created_by` | Nonblank actor | Blank |
| `ck_company_site_contacts_updated_by` | Nonblank actor | Blank |
| `ck_company_site_contacts_audit_dates` | Equal/later update | Earlier update |
| `ck_company_site_contacts_deletion` | Both NULL; or coherent deletion/nonprimary | Missing actor/date, blank actor, earlier date, deleted primary |
| `uq_company_site_contacts_current_value` | Different Site/type/value; deleted history reusable | Exact duplicate current Site/type/value |
| `uq_company_site_primary_contact` | One current primary per Site/type | Second current primary, different value |
| `trg_company_site_contacts_immutable` | Mutable value/label | Protected field mutation |
| `ck_company_site_child_immutable_identity` (shared trigger diagnostic) | Original id/Site/creation | Change each of id, site_id, created_at, created_by |

## Idempotency — verified suite `IdempotencyIntegrityTest`

`./gradlew test --tests '*IdempotencyIntegrityTest'`: **4 tests passed** on
PostgreSQL 18 with the same Podman environment.

- `compositeKeyAndOriginalSnapshot`: composite PK, key/operation separation,
  nullable Site/version, version 0/int64 maximum, exact opaque snapshot fields.
- `qualifiedReferencesRejectOtherTenantCompanyAndUnknownSite`: both qualified
  FKs, cross-tenant/cross-Company/unknown references, nullable Site acceptance,
  RESTRICT deletion, Site FK catalog definition and owner index.
- `everyCheckAndJsonParserRejectInvalidCompletion`: all eight CHECKs below,
  HTTP bounds, positive snapshot versions, non-object and malformed JSON.
- `mandatoryFieldsRejectSqlNull`: every required completion field.

| Object | Valid case | Invalid case |
| --- | --- | --- |
| `pk_company_idempotency_records` | Reuse key with different tenant or operation | Duplicate tenant/operation/key |
| `fk_company_idempotency_company` | Company in tenant, Site NULL | Unknown Company, cross-tenant, referenced Company deletion |
| `fk_company_idempotency_site` | NULL or Site of same tenant/Company | Other Company, other tenant, unknown Site, referenced Site deletion |
| `ck_company_idempotency_operation` | Nonblank operation preserved | Blank |
| `ck_company_idempotency_key` | `Key-aB` preserved | Blank |
| `ck_company_idempotency_hash` | 64 lowercase hex characters | Uppercase, nonhex, wrong length |
| `ck_company_idempotency_expected_version` | NULL, 0, positive | -1 |
| `ck_company_idempotency_http_status` | 200, 201, 299 | 199, 300 |
| `ck_company_idempotency_snapshot_version` | 1 and higher | 0, negative |
| `ck_company_idempotency_snapshot` | JSON object, nested opaque strings | JSON null, array, scalar; malformed JSON rejected by JSONB parser |
| `ck_company_idempotency_created_by` | Nonblank actor | Blank |
| `ix_company_idempotency_owner` | Keys `(tenant_id,company_id,site_id)` | Missing/reordered keys detected |

## Outbox — verified suite `OutboxIntegrityTest`

`./gradlew test --tests '*OutboxIntegrityTest'`: **5 tests passed** on PostgreSQL
18 with the same Podman environment.

- `defaultMetadataOpaquePayloadAndIdentity`: PK, event identity UNIQUE and exact
  key definition, UUIDv7/defaults, opaque JSONB/correlation/audit, separate versions.
- `everyEventHasCorrectAggregateAndCreationVersion`: all seven events and their
  allow-list catalog, swapped aggregate rejection, creation=0/noncreation>0.
  Unknown event rejection overlaps the mapping CHECK; the catalog assertion
  independently verifies the event-type allow-list.
- `jsonVersionsCorrelationAttemptsAndAuditChecks`: all schema/payload/correlation/
  causation/attempts/version/actor/audit CHECKs and malformed JSONB.
- `publicationAndFailureMetadataMatchStatus`: PUBLISHED timestamp iff published;
  FAILED diagnostic required, valid instances of all delivery states.
- `enumsMandatoryFieldsAndPollingIndexes`: both enum sets/rejection, all mandatory
  fields and exact delivery/correlation index definitions.

| Object | Valid case | Invalid case |
| --- | --- | --- |
| `pk_company_outbox_events` | Distinct IDs | Duplicate id |
| `uq_company_outbox_event_identity` | Distinct tenant/type/id/version/event | Exact duplicate identity |
| `ck_company_outbox_event_type` | All seven V1 event types | Unknown event |
| `ck_company_outbox_aggregate_mapping` | Company events COMPANY; Site events COMPANY_SITE | Swapped aggregate types |
| `ck_company_outbox_creation_version` | Created=0; every other event>0 | Created negative/positive; noncreated<=0 |
| `ck_company_outbox_schema_version` | Positive | 0, negative |
| `ck_company_outbox_payload` | JSON object preserving opaque strings | JSON null, array, scalar; malformed JSON rejected by parser |
| `ck_company_outbox_correlation_id` | Nonblank case-preserved correlation | Blank |
| `ck_company_outbox_causation_id` | NULL/nonblank case-preserved causation | Blank |
| `ck_company_outbox_attempts` | 0 and positive | -1 |
| `ck_company_outbox_version` | 0 and positive, independent of aggregate version | -1 |
| `ck_company_outbox_published_at` | PUBLISHED with timestamp; other states without | PUBLISHED without timestamp; other states with timestamp |
| `ck_company_outbox_failed_error` | FAILED with nonblank code; other states NULL allowed | FAILED with NULL/blank code |
| `ck_company_outbox_created_by` | Nonblank actor | Blank |
| `ck_company_outbox_updated_by` | Nonblank actor | Blank |
| `ck_company_outbox_audit_dates` | Equal/later update | Earlier update |
| `ix_company_outbox_delivery` | Keys `(status,next_attempt_at,created_at,id)` | Missing/reordered keys detected |
| `ix_company_outbox_correlation` | Key correlation_id | Missing/wrong key detected |

## Catalog and mutation assertions

The catalog suite must compare all named constraints/index definitions (including
partial predicates, descending keys, FK columns and RESTRICT actions), seven enum
value sets and four enabled trigger/function bindings. Trigger functions:
`reject_company_immutable_changes`, `reject_company_site_immutable_changes`,
`reject_company_site_child_identity_change`. Each valid update exercises its
binding; each protected-field mutation exercises the negative branch. Diagnostic
CHECK names raised by triggers are not rows in `pg_constraint`.

Redundant tenant-qualified unique keys cannot independently fail before the PK
on the same id. Their catalog definitions and successful/unsuccessful qualified
child references establish their purpose; do not claim a separately isolated
uniqueness violation that PostgreSQL cannot produce.

Ordinary indexes do not reject writes. Their negative cases are catalog-definition
assertions that fail if the index is missing or weakened, rather than fabricated
invalid DML. Partial unique indexes additionally require positive/negative DML.

## Migration decision (task 5.8)

The combined `./gradlew test` run passed **86 tests** using the Podman environment
above, including all six integrity suites, `SchemaCatalogIntegrityTest`,
`SchemaDocumentationTest`, `DatabaseStartupTest`, text compatibility, pure Domain
and architecture tests. No tests were skipped in this run. PostgreSQL 18 installs
V1 cleanly and repeated Flyway migrate/validate preserves its history/checksum
(`DatabaseStartupTest.repeatedStartupMigrationAndValidationLeaveHistoryUnchanged`).

`SchemaCatalogIntegrityTest.everyDeclaredConstraintIndexAndTriggerIsInstalledAndEnabled`
compares the installed set of named PK/UNIQUE/FK/CHECKs, indexes, enabled triggers
and bound functions with V1. It additionally checks validated constraints and
ready/valid indexes. PostgreSQL 18-generated NOT NULL catalog entries are separate
from V1's explicitly named constraints; NOT NULL behavior is tested via DML.

These tests demonstrated no contractual defect requiring another migration.
V1 remains byte-for-byte unchanged in the working tree (`git diff --
src/main/resources/db/migration/V1__create_company_registry_schema.sql` is empty),
and no V2 or repair was introduced. There is no separate upgrade migration to test;
the repeated-start/history test covers the existing V1 installation. Native
execution and operational reactive mappers remain tracked in later tasks.
