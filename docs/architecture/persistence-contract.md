# Reactive persistence boundary

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
