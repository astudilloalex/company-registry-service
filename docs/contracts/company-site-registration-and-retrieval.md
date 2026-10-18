# Company Site registration and retrieval (HU04)

All three operations require exactly one Tenant-Id (canonical lowercase UUID),
User-Id (exact opaque actor) and Process-Id (canonical lowercase UUID). Responses
preserve the current valid Process-Id. They resolve Company/Site locally with
no Party, geographic or broker calls.

## Register and replay

```http
POST /v1/companies/018f1234-5678-7000-8000-000000000004/sites
Tenant-Id: 018f1234-5678-7000-8000-000000000001
User-Id: Actor-aB
Process-Id: 018f1234-5678-7000-8000-000000000002
Idempotency-Key: Site-Key-aB
Content-Type: application/json

{"code":" ß ","name":" compañía  ágil "}
```

The closed body accepts only code/name/siteType. Canonical code is SS, name is
COMPAÑÍA  ÁGIL; limits are 64/200 Unicode code points after strip/uppercase.
Omitted type means BRANCH. Explicit null fails; exact HEADQUARTERS/BRANCH/WAREHOUSE/OTHER
are accepted. No If-Match is required. New registration under DRAFT/ACTIVE/INACTIVE
Company yields 201 successful, Site UUIDv7, DRAFT/version 0/false, equal creation/update
microsecond UTC timestamps and exact actors. Company state/version/audit are untouched.

The shared envelope's data contains exactly siteId, companyId, code, name, siteType,
status, version, effectivelyOperational, createdAt, createdBy, updatedAt, updatedBy.
It contains no tenant, Party, addresses or contacts.

Replaying the same key and effective content yields the original 201 and all twelve
values, even after Site changes/closes or Company archives. The current response
Process-Id may differ. Explicit BRANCH and canonical equivalent text are equivalent;
a Company change is significant. Keys are scoped by tenant/operation/key, preserved
exactly with no TTL. Keys/policies/codecs remain available after rotation/restart.

All types lock Company and recheck acceptance before admission. Site/completion/enabled
outbox commit together. ARCHIVED rejects new registration. Code is reserved even
by CLOSED. CLOSED headquarters releases the headquarters slot, permitting a new
HEADQUARTERS with a free code; it does not release the old code. Other Companies
can use that code. No closure/activation/archival endpoint is introduced by HU04.

## Read and page

```http
GET /v1/companies/018f1234-5678-7000-8000-000000000004/sites/018f1234-5678-7000-8000-000000000005
Tenant-Id: 018f1234-5678-7000-8000-000000000001
User-Id: Actor-aB
Process-Id: 018f1234-5678-7000-8000-000000000002
```

```http
GET /v1/companies/018f1234-5678-7000-8000-000000000004/sites?siteType=BRANCH&status=ACTIVE&limit=50
Tenant-Id: 018f1234-5678-7000-8000-000000000001
User-Id: Actor-aB
Process-Id: 018f1234-5678-7000-8000-000000000002
```

GET returns 200 successful; reads require no mutation headers. Parent absent/alien
is company-not-found, then Site absent/outside Company is site-not-found. Own Company
without matches yields data: [] and numberOfElements: 0. CLOSED and ARCHIVED stay
readable. EffectivelyOperational is true exactly for joined Company ACTIVE and Site
ACTIVE; status filtering concerns Site's own state. Replay never recalculates it.

Only code/siteType/status/cursor/limit are allowed once each. Filters combine with
AND. Code is canonical; enums are exact. Limit defaults to 50 and accepts 1–200.
The order is created_at DESC/id DESC, including the full timestamp/UUID tie break.
The shared envelope puts numberOfElements and optional nextCursor at top level;
there are no totals, prevCursor or nested pagination. Pass nextCursor with the same
effective filters, Company and tenant to continue. Actor/process/limit can change.
Cursors are authenticated and exclusive to Sites, with a maximum of 4096 characters.
Navigation observes current data and does not promise a frozen multi-request snapshot.

## Errors

Known HU04 errors contain exactly status and code, e.g.
`{"status":409,"code":"headquarters-already-exists"}`. Structural body precedence
is malformed document/trailing tokens, non-object root, unknown fields, duplicates,
then code/name/siteType (required/type before canonical length). No submitted values,
parser/SQL messages or errors[] replace the root code. Shared context/media/framework
errors retain their existing contracts; unexpected failures are sanitized server-error.

| HTTP | code |
| --- | --- |
| 404 | `company-not-found` |
| 404 | `site-not-found` |
| 409 | `site-code-already-exists` |
| 409 | `headquarters-already-exists` |
| 409 | `idempotency-key-already-used-with-different-request` |
| 409 | `site-registration-not-allowed-for-archived-company` |
| 503 | `dependency-unavailable` |
| 400 | `site-code-required` |
| 400 | `site-code-too-long` |
| 400 | `site-name-required` |
| 400 | `site-name-too-long` |
| 400 | `site-type-null-not-allowed` |
| 400 | `site-type-not-supported` |
| 400 | `site-code-must-be-string` |
| 400 | `site-name-must-be-string` |
| 400 | `site-type-must-be-string` |
| 400 | `request-body-required` |
| 400 | `request-body-invalid-json` |
| 400 | `request-body-must-be-object` |
| 400 | `request-body-contains-unknown-fields` |
| 400 | `request-body-contains-duplicate-fields` |
| 400 | `company-id-invalid` |
| 400 | `site-id-invalid` |
| 400 | `idempotency-key-required` |
| 400 | `idempotency-key-duplicated` |
| 400 | `idempotency-key-blank` |
| 400 | `idempotency-key-too-long` |
| 400 | `query-parameter-not-supported` |
| 400 | `query-parameter-repeated` |
| 400 | `site-status-not-supported` |
| 400 | `site-page-limit-must-be-an-integer` |
| 400 | `site-page-limit-must-be-between-1-and-200` |
| 400 | `site-cursor-invalid-or-incompatible` |

Transient unresolved database/commit uncertainty returns 503 dependency-unavailable.
Retry the same key/input. Recovery never blindly re-executes the write. See
[the persistence contract](../architecture/persistence-contract.md) for the shared
10s configurable budget, Company → Site locking and compatible deployment rollback.

The [Site event schema](events/company.site.created.v1.schema.json) describes the
original eight-field payload in the eleven-field envelope. Disabled stores zero;
stored-only/published store one PENDING with counters zero, without broker access.
Publication remains HU08. Executed acceptance evidence belongs in the HU04 review;
contract publication alone does not establish native or concurrency acceptance.

## HU05 companion operations

See [Site information and lifecycle](company-site-lifecycle-management.md) for PATCH
and activate/deactivate/close. HU04 registration keeps its BRANCH default, required
creation key and DRAFT/0/false replay. GET keeps current operativity; HU05 replay
keeps its accepted boolean. A committed rename releases the previous code; CLOSED
retains its current code. Company lifecycle endpoints remain outside HU04/HU05.
