# Reactive persistence boundary

## HU05 schema extension

V5 adds `ck_company_idempotency_site_mutation_metadata` without changing V1–V4,
the six application tables, existing identities or snapshots. Each completion in
company.site.update.v1, company.site.activate.v1, company.site.deactivate.v1 or
company.site.close.v1 requires an owned Site, original nonnegative expected Site
version, result status 200, positive fingerprint policy version and valid key ID.
The existing composite foreign keys, lowercase 64-character hash and versioned
JSON-object snapshot constraints still apply, including to PATCH no-op acceptances.
The identity remains tenant/operation/key; resource IDs do not extend the primary key.

Before installing the CHECK, V5 stops with a controlled 23514 diagnostic if historical
HU05 metadata is incompatible. Operators must inspect original provenance; migration
never guesses a key, repairs a snapshot or deletes an acceptance. Previous operation
namespaces retain their contracts. Runtime remains DML-only and Flyway uses the
separate migration role. A deployment rollback must preserve V5 and confirmed data.

HU05 writers acquire the tenant-qualified Company lock before the qualified Site
lock in one sequential transaction. They revalidate ownership/version/states after
locking. `SiteRepository.update` uses a single bulk UPDATE with tenant/Company/Site
and expected-version predicates, setting only code/name/type/status/update audit and
version+1. It does not mutate the managed entity or Company; zero rows is the neutral
STALE_VERSION outcome. No-op must omit this UPDATE. Diagnostics exclude the current
Site and remain qualified to its Company/tenant. SQL unique indexes remain final
arbiters; a committed rename frees its prior code, while rollback retains it.

The existing failure translator classifies SITE_CODE, SITE_HEADQUARTERS and
COMPLETION_KEY uniquely and leaves unknown uniqueness as UNEXPECTED. A durable
completion lookup after confirmed transaction finalization must precede translating
those failures at the use-case boundary. Future Company and child writers must
respect Company → Site; children share Site's one conditional revision increment.

## Port ownership

Application owns `CompanyRepository`, `SiteRepository`, `CompletionRepository`,
`OutboxRepository` and `AtomicOperation`. Their `RegistryData` projections carry
pure aggregate concepts, audit/identity/version data and neutral object maps for
versioned snapshots/events. They expose no ORM, Jackson, SQL or HTTP API types.
The stored completion's numeric result status is original accepted outcome
metadata, not a transport response or HTTP status decision in Application.

`find` emits null when absent in the requested scope. Child lists emit empty
lists for absent/foreign ownership and include retained soft-deleted history;
consumers explicitly select current records. Child writes must first resolve the
Site by tenant, Company and Site ID. No global tenant filter substitutes for
qualified queries. Every operational method requires the outer reactive session
boundary and the originating Vert.x context.

`AtomicOperation.execute` wraps one **sequential** operation in a single local
Panache transaction: Company/Site mutation, accepted completion and enabled
outbox insertion commit or roll back together. No concurrent work shares its
session; no manual subscription or wait belongs in an adapter. Provider calls
must precede prolonged database locks, with lock order Company→Site in future
workflows. `read` supplies a session without a write transaction.

Company and Site start at version 0. Address/Contact mutations will increment
their Site's version in future workflows. Effective aggregate changes increment
once; no-op/replay do not. Outbox aggregate version and optimistic technical
version are independent. T00 ports intentionally offer insert/read operations
for foundation verification, not registration, activation, replay or generic CRUD
workflows. Future mutation ports must carry expected aggregate versions.

Technical failures are translated to `PersistenceFailure` at the Infrastructure
boundary. Known uniqueness, referential integrity, invalid data, stale versions
and unavailable dependencies remain distinguishable. Domain/Application failures
and cancellation retain their original meaning. API owns public error/status
translation and never exposes a driver exception, SQL or diagnostic cause.

JSONB uses an isolated Hibernate `@JsonFormat @PersistenceUnitExtension`
`FormatMapper`, not the CDI HTTP ObjectMapper. Quarkus 3.40.1 correctly rejects
the implicit shared mapper because `api-response` customizes it. The persistence
mapper retains explicit null keys and opaque values, independently of REST null
omission. A real JSONB round-trip tests this distinction; no compatibility-check
override is used to silence the failure.

## JVM evidence

PostgreSQL 18/Podman tests: `AggregateMappingTest`, `ChildMappingTest`,
`TechnicalMappingTest` and `AtomicOperationTest` execute on `@RunOnVertxContext`
with `UniAsserter`. They cover all six mappings, named enums, Instant, JSONB with
explicit nulls, initial/separate versions, qualified reads/writes, composite
completion keys, successful commit and rollback of all three atomic records for
both business and real database failures. `PersistenceFailuresTest` verifies
stale/unavailable classification and preservation of business/cancellation
failures. `ArchitectureTest` passes with the real Application port signatures.
Hibernate Reactive reports SQLSTATE through Hibernate's `JDBCException`
abstraction in some failures; inspecting it performs no JDBC operation.
Native verification remains separately tracked in section 11.

## HU01 completion provenance (V2)

`RegistryData.CompletionRecord` and `CompletionEntity` carry nullable
`fingerprintVersion`/`fingerprintKeyId`. The compatibility constructor preserves
null provenance for historical technical namespaces. Every `company.create.v1`
acceptance supplies both explicitly; SQL enforces a positive version, bounded
nonblank key ID and creation outcome shape. The key ID references externally
managed material and is not a secret. Never infer it from the current active key.

The result JSONB document preserves the complete accepted public Company/Party
projection, masked identifiers, boolean/version types, ISO Instant/LocalDate,
opaque URLs/actors and explicit internal nulls. It remains separate from HTTP
null omission. No legal columns or mirror tables are introduced. V2 preserves
V1 and its checksum, existing snapshots and composite ownership references.

## HU02 completion shape (V3)

V3 adds `ck_company_idempotency_update_metadata` to the existing completion table.
For `company.update.v1` it requires fingerprint provenance, original expected
version >= 0, result status 200 and null Site. Existing hash, JSON object,
positive snapshot version and tenant-qualified ownership constraints still apply.
The schema retains six application tables and the separate runtime DML/migration
roles. V1/V2 migrations and HU01 enriched snapshots are preserved.
Incompatible historical HU02 rows stop the migration with a controlled diagnostic;
no provenance is inferred, no snapshot is reconstructed and no acceptance is
removed. Both effective changes and durable no-ops must satisfy V3. During
application rollback retain V3, accepted data and historical keys; restore a
compatible application or suspend the affected routes.

HU02 `CompanyRepository.findForUpdate` resolves tenant and Company ID with
PESSIMISTIC_WRITE (PostgreSQL FOR UPDATE) inside AtomicOperation. The protected
point applies to no-op too; no-op issues no Company UPDATE. `update` carries the
original expected version and changes only code/name/description/URL, update
audit and version+1 using a single bulk HQL statement with tenant/ID/version
predicates. Loaded entities remain unmodified, so @Version adds no extra flush.
Response values come from that single write; accepted timestamps are microseconds.
Site state and creation audit/identity/status are preserved. Missing/stale update
predicates produce neutral STALE_VERSION. Known code/completion uniqueness and
transient deadlock/serialization failures retain distinct recovery semantics.

UpdateCompany shares one DatabaseBudget across lookup, row wait, writes, cleanup
and recovery. Transaction-local statement_timeout/lock_timeout use remaining time;
failed session rollback/closure precedes recovery in a new session. No parallel
operations share a session, no manual background subscription performs writes and
no unkeyed uncertain command is automatically reexecuted. `occupied` is never
used by HU02: own Party cannot prove either code conflict or accepted outcome.
# HU04 schema evolution

V4 adds `ck_company_idempotency_site_creation_metadata` and
`ix_company_sites_tenant_company_created` without altering V1–V3 or adding tables.
The acceptance for `company.site.create.v1` requires an owned Site, null expected
version, status 201 and fingerprint provenance. Existing hash, JSON-object,
snapshot-version and composite tenant/Company/Site foreign keys remain authoritative.
Before adding the CHECK, migration diagnoses incompatible historical HU04 rows and
stops; operators must retain snapshots and establish provenance from evidence,
never manufacture keys or reconstruct an original response from current state.

The Site index orders `(tenant_id, company_id, created_at DESC, id DESC)` for
forward pagination. Runtime DML and migrator DDL roles remain separate. CLOSED
preserves the code's uniqueness while releasing the open-headquarters slot.

New registration locks tenant-qualified Company first for all Site types, then
rechecks the accepted completion before current admission. Company status/version
and audit are never updated. Future lifecycle writers must follow Company → Site.
The short initial completion lookup precedes this transaction. A losing write
rolls back and closes before a primary-database lookup in a fresh session:
equivalent completion wins over current state/uniqueness; another intent gets
the idempotency conflict; known Site-code/HQ uniqueness retains its specific cause.
Unresolved commit/timeout returns 503 without blind re-execution. Cancellation
stays on the same subscription, with no independently subscribed write.

`company-registry.site-registration.wait-budget` reads
`COMPANY_SITE_REGISTRATION_WAIT_BUDGET` (default 10s, positive finite). One shared
DatabaseBudget and a subscription deadline cover all local phases, including
connection acquisition, recovery, commit and cancellation/cleanup waits;
checkpoints also set remaining statement_timeout/lock_timeout. Never restart
the budget between phases. Each atomic phase reserves the smaller of 10% of
its remaining time and 100 ms for normal rollback/closure. Unfinished work fails
inside the transaction before that reserve; the outer deadline bounds stalled
acquisition, finalization and cleanup as well. Timer/cancellation signals return
to the owning Vert.x context, and cleanup is awaited only for the remaining
phase time. There is one write subscription and no automatic creation retry.
The deadline failure records that transaction finalization is still uncertain;
it cannot enter automatic recovery in that session. Only failures emitted after
confirmed rollback/closure may open fresh-session recovery. This rule also
preserves the shared Company operations' recovery protocol.
An expired deadline returns 503 without asserting that a remote commit rolled
back: a later retry resolves its durable acceptance. Deadlock and serialization
aborts are transient uncertainty, not an invented business conflict.

For rollback after HU04 acceptance, retain V4, Sites, completions, events and
historical keys/codecs. Restore a replay-capable application or suspend Site
routes; never delete accepted keys or rewrite snapshots to downgrade.

Site detail/page first resolve the parent in tenant, then read Site and Company
state together with a local join. The earlier existence check does not supply
the operativity state. CLOSED/ARCHIVED remain readable. Exact canonical code,
siteType and status filters combine with AND. Queries fetch limit+1, order by
created_at/id descending and preserve both values as the forward boundary.
Cursor scope includes tenant, Company, effective filters and order with the
company.site.cursor.v1 HMAC domain; actor/process/limit are excluded. No global
count, Party, address/contact load or write lock belongs to this read path.

## HU05 accepted result format 1

HU05 uses its own codec, preserving the HU04 DRAFT/0 creation format. Its closed
snapshot has `site`, `companyStatus` and `effectivelyOperational`; Site retains the
full qualified identity, code/name/type/status/revision and audit. Original Company
status verifies the stored boolean without any query to present aggregate state.
Only the twelve Site response fields are public. UPDATE may retain expectedVersion
(no-op) or advance once; lifecycle commands must advance once and match their target
state. A no-op completion has its own acceptance time/actor and need not match Site's
unchanged update audit. Types, ownership, UTC microseconds, status and revision are
validated; corruption or unsupported format/policy/key is an internal error.

Policy 1 signs tenant/operation/Company/Site/original version and canonical partial
intent with presence. Each namespace independently owns tenant/operation/key. Retain
formats, policies, snapshots and keyring material without TTL. Rotation to K2 retains
K1; rollback preserves V5 and all confirmed results/events, using compatible code or
suspending affected routes. Never reconstruct or rewrite history from current rows.

## Evidence boundary for compatible future writers

The HU05 concurrency suite uses independent HTTP requests/JDBC transactions and
observable lock barriers. Its Company activation/archival and address/contact writers
are test-only fixtures following Company → Site. Child fixtures compare the common
Site revision, insert a child and increment Site once in the same transaction; a
stale child attempt writes nothing. These tests establish local lock/revision
compatibility only. They do not implement HU03/HU06/HU07 endpoints, remote Party
eligibility or distributed provider atomicity. Production artifacts contain none of
these fixture classes; actual endpoint integration remains owned by those stories.
