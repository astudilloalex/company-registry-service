# Reactive persistence boundary

## HU03 transition storage and V6

HU03 fingerprints bind ordered fingerprintVersion (numeric 1), canonical tenantId,
operation, canonical companyId, decimal-string expectedVersion and request `{}`.
The three operation scopes are independent. Body absent/`{}` are equivalent;
actor/process/key/outbox mode and Party/matrix observations are excluded. A fresh
MAC is used per request with the existing external keyring. K1→K2 rotation retains
both original secrets and selects stored policy/K1 for old completions. No TTL,
cleanup or active-key fallback applies to accepted results or historical material.
Unknown policy/key/format or corrupt snapshots fail sanitized, without repair.

LifecycleResultCodec is independent of the HU02 codec. It retains explicit nulls
and strict UUID/string/integer/enum/UTC microsecond types, validates tenant/Company,
operation target and exactly expected+1 (without overflow), and requires completion
date/actor to match snapshot updatedAt/updatedBy. Its response omits null optionals.
The existing CompanyRecord/Audit/Company/AggregateVersion native metadata is reused,
with CompanyStatus registered at the Infrastructure boundary.

Lookup precedes new acceptance. Activation preparation locks Company, rechecks
completion, checks ownership/revision/transition, then locks/resolves headquarters.
It writes nothing and finalizes before Party HTTP. Both returned Party facts and
expected dependency failures remain neutral until final acceptance repeats that
entire local sequence. Replay wins over current state and a later remote failure.
Deactivation/archive use one local acceptance transaction and never call providers.
One LifecycleDeadline starts on subscription and keeps running through HTTP,
commit, cancellation cleanup and recovery; each boundary derives its remaining
budget. Existing DatabaseBudget behavior is unchanged. AtomicOperation provides
server statement/lock timeouts and cleanup reserve. Recovery only reads completion
after failed transaction finalization/session closure; uncertain finalization or
exhaustion returns 503 and never resubscribes a write.

V6 adds `ck_company_idempotency_lifecycle_metadata` to the existing completion
table. The three Company lifecycle namespaces require site_id null, nonnull
nonnegative expected revision, status 200, positive fingerprint policy and valid
key ID. Before installation incompatible history stops with 23514 and a controlled
inspection hint. Snapshots/provenance are preserved; V1–V5 stay immutable. Six
application tables, tenant-qualified foreign keys, the current-headquarters partial
unique index and the runtime DML/migrator DDL separation remain unchanged.

`CompanyRepository.transition` is distinct from HU02 information update. It
qualifies tenant/id/expected revision/observed source status and writes only status,
updatedAt/updatedBy and version=version+1 through bulk HQL. The result revision must
be exactly expected+1; overflow is rejected before a write. Managed entities are
not mutated, so @Version cannot add another increment. A fresh session must verify
the persisted result. Zero rows yields the neutral stale-version failure.

`SiteRepository.currentHeadquartersForUpdate` runs after Company protection,
qualifies tenant/Company, excludes CLOSED and locks the current HEADQUARTERS.
Absence is protected against competing creation through the parent lock. Multiple
current rows are an internal integrity failure. Acquiring either lock never
changes audit/version or children, and Company transitions do not write Sites.

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
unchanged update audit. Clock skew between instances can place its acceptance time
before the Site's prior update time; that ordering does not invalidate the no-op or
its replay. Effective changes still require the same acceptance time and actor in
their Site audit. Types, ownership, UTC microseconds, status and revision are
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
compatibility only. They do not implement HU06/HU07 endpoints; HU03 adds separate protected activation with remote Party
eligibility or distributed provider atomicity. Production artifacts contain none of
these fixture classes; actual endpoint integration remains owned by those stories.


## HU06 incremental storage (V7)

V7 preserves the six business tables and V1–V6 checksums. Address completions
require the original Site precondition for all three operation namespaces,
including CREATE, and retain the common tenant/operation/key identity. Metadata
preflight stops incompatible history without rewriting it. The partial address
page index orders current children by Site, creation descending and ID descending.
The immediate per-type primary index, immutable identity/creation audit triggers,
coordinate pair checks and runtime/migrator privileges remain authoritative.


### HU06 policy, format, cursors and retention

The independent `AddressFingerprintV1` policy signs compact ordered UTF-8 JSON:
`fingerprintVersion,tenantId,operation,companyId,siteId,addressId,expectedVersion,request`.
CREATE has null addressId, materializes canonical defaults and requires a key;
UPDATE signs field presence separately from null/value; DELETE signs `{}`.
Request order is addressType,countryCode,addressLine1,administrativeDivisionCode,
addressLine2,houseNumber,postalCode,latitude,longitude,isPrimary. Coordinates in
signed material are canonical plain decimal strings; HTTP/snapshots keep numbers.
The original Site precondition is a decimal string. Actor, process, key text,
outbox mode and generated identifiers are excluded.

`AddressResultCodec` format 1 stores operation and a closed result containing
qualified ownership and the accepted address or deletion receipt. It preserves
internal nulls, original revision and audit, checks exact scalar shapes, ownership,
address identity and expected revision (unchanged only for UPDATE no-op).
CREATE requires UUIDv7; UPDATE/DELETE preserve an existing valid UUID identity.
Unknown/corrupt material fails closed without consulting current rows. HU01–HU05
codecs and policies remain independent. The common JSONB mapping keeps nulls;
address decode validates numbers before producing exact BigDecimal values.

`AuthenticatedAddressCursors` format 1 uses the HMAC domain
`company.site.address.cursor.v1`, a key ID, tenant/Company/Site, effective filters,
fixed descending creation/ID order and boundary. It excludes actor/process,
page size and Site revision. A page reads current values; continuation does not
promise a cross-request snapshot. Cursor length is limited to 4096 characters.

The external fingerprint keyring configures HU06 through the same active key ID
and retained historical key map. Rotate K1 to K2 by distributing both keys to
all instances before selecting K2. Retain K1, policy 1, format 1 and accepted rows
without TTL. Rollback retains V7, all accepted/deleted rows, completions and outbox
records; run compatible code or suspend HU06 routes. Never delete accepted keys,
restore deleted addresses or reset revisions to permit a second acceptance.
`AddressAcceptanceTest` and `AddressCursorTest` verify independent instances,
K1/K2, original snapshots, formats, ownership and corruption handling.

HU06 uses `AddressDeadline`, default 10s via
`COMPANY_SITE_ADDRESS_WAIT_BUDGET`, validated as positive finite nanoseconds.
One monotonic deadline covers lookup, Company then Site protection, finalized
preparation, geographic HTTP, final revalidation, writes, commit/close and recovery.
Every database phase receives only the remaining budget and AtomicOperation's
existing cleanup reserve. Geography runs after the preparation transaction closes.
Unknown finalization returns unavailable without starting recovery or another
mutation. Finalized failures can look up an existing acceptance once; writes
are never automatically reexecuted. Other Site writers must use the same
Company-before-Site protocol and shared revision, including other child writers.
