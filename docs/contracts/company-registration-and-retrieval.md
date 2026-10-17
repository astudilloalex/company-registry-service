# HU01 registration and retrieval

All three routes require exactly one canonical lowercase UUID Tenant-Id and
Process-Id and one exact User-Id (nonblank, at most 128 code points, no controls).
Context is checked before lookup, including replay. Valid Process-Id is echoed
on failures; invalid Process-Id is omitted. Every Party request/page receives the
same three values. Health/OpenAPI work without business headers.

POST /v1/companies additionally requires exactly one nonblank opaque
Idempotency-Key, at most 128 code points. Only partyId, code, operatingName,
description and logoUrl are accepted. Business text uses strip/Locale.ROOT
uppercase before code point limits of 64/200/500, retaining accents and internal
spaces. Omitted/null optionals are equivalent; supplied blanks fail. logoUrl is
absolute HTTP/HTTPS, at most 1000 code points, with no outer whitespace; its
case-sensitive content is preserved. UUID references need not be UUIDv7.

New registration admits tenant-scoped LEGAL_ENTITY Party in DRAFT/ACTIVE/INACTIVE,
rejects ARCHIVED/NATURAL_PERSON, and requires complete public detail/identifiers.
Company starts with server UUIDv7, DRAFT, version 0 and a single UTC microsecond
instant for both audit dates. Both actors equal the original exact User-Id.
Company, durable completion and enabled creation event commit together before
201 is returned. disabled omits events; stored-only/published store PENDING and
never contact RabbitMQ. See the OpenAPI examples and event handoff.

| Condition | Result |
| --- | --- |
| Accepted tenant/operation/key with equivalent canonical input | Original enriched 201 snapshot; no providers or writes |
| Same key, different effective input | 409 resource-conflict |
| Different key, same tenant Party or canonical code | 409 resource-conflict, even after Company archival |
| Missing Party in current tenant on creation | 404 reference-not-found |
| Incompatible Party type/state on creation | 400 reference-invalid |
| Invalid closed body, header/key or query | 400 stable public error |
| Company absent/foreign on GET | Identical 404 not-found; no provider calls |
| Provider contract invalid or persisted reference unresolvable | 502 dependency-response-invalid |
| Dependency timeout/unavailable or unresolved acceptance | 503 dependency-unavailable |
| Unknown/corrupt historical policy/key/snapshot or unexpected persistence fault | Sanitized 500 server-error |

PostgreSQL uniqueness arbitrates concurrent requests. After the losing write
rolls back and its session closes, a fresh primary-database read resolves the
completion. It verifies the winning record's original fingerprint policy/key,
not the losing request's current active key. A matching acceptance replays;
different content conflicts; absent completion plus occupied code/Party is a
business conflict. Unknown uniqueness violations are not business conflicts.
A rolled-back holder permits another eligible request to confirm.

`company-registry.registration.wait-budget` defaults to 10s across local lookup,
write and recovery phases; external enrichment has its separate 30s deadline.
Time spent in each local database phase, including cleanup, reduces the same
remaining budget. Before each query/write, transaction-local PostgreSQL
statement_timeout and lock_timeout use that remaining time. Server cancellation
finishes rollback/session closure before recovery. No session holds locks while
waiting on Party. An interrupted commit can have succeeded: attempt durable
recovery, otherwise return 503 and retry the identical key/input. Never infer
rollback from a lost response or persist a failure as an acceptance.

GET /v1/companies/{companyId} and GET /v1/companies show current Company and Party
public fields. Party ARCHIVED can be shown on reads. Reads/replays do not change
audits, versions or events. A replay instead retains the original Company,
Party, legal details, identifiers and actors even after current data changes,
provider failure, outbox-mode changes, key rotation or process restart. Its
HTTP Process-Id belongs to the current attempt; event correlation stays original.

List accepts code, partyId, status, cursor and limit once each, combining exact
filters with AND within the tenant. Code uses registration canonicalization;
status includes ARCHIVED. Invalid/blank/unknown/repeated query values are 400.
Limits are 1–200, default 50. Forward pages use created_at DESC, id DESC with
limit+1 and a boundary containing both full-precision timestamp and ID. The
versioned authenticated cursor binds tenant, effective filters and order under
purpose-separated HMAC, retaining historical keys. Limit may change; actor and
process are excluded from scope. Invalid/unknown/altered/incompatible cursors are
400. Data is always an array; numberOfElements and optional nextCursor are
top-level. Totals/backward cursors are omitted. There is no cross-request snapshot.

Identifiers include every historical provider status in provider order, following
all nextCursor pages at constant limit 200. They expose maskedValue, not full
identifiers, and preserve public optional metadata. Empty means confirmed empty.
Enrichment closes local sessions first, bounds HTTP concurrency and applies a
global deadline including queued calls and every page. Errors fail the complete
response; they never produce partial success or invented empty enrichment.

The status-filtered query was measured on 10,000 fixture rows (90% DRAFT). The
existing ix_companies_tenant_created index scanned 57 entries to return 51,
removing six by status, with four shared buffers and observed execution 0.102 ms.
No incremental index is justified by this measurement. Reproduce via
CompanyRegistrationHttpTest.statusFilteredForwardPageUsesTheExistingTenantOrderIndex;
its plan is saved under build/reports/hu01/status-page-explain.txt. Timing is local
evidence rather than a production latency guarantee.
