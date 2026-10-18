# Company lifecycle management (HU03)

POST `/v1/companies/{companyId}/activate`, `/deactivate`, `/archive` requires one
Tenant-Id, User-Id and Process-Id according to existing business context conventions.
If-Match is one canonical nonnegative int64 decimal Company revision (outer HTTP
OWS allowed). Idempotency-Key is optional: one opaque nonblank value up to 128
Unicode code points, retained exactly. Body absent and `{}` are equivalent; a body
with content requires application/json. Other roots, properties or trailing tokens
are rejected before replay. Success is 200 `successful` with CompanyOperationalResponse:
companyId, partyId, code, operatingName, status, version and all four audit fields;
description/logoUrl only when nonnull. No tenantId, Party projection or Site data.

| Command | Effective transitions | Additional prerequisite | Event |
| --- | --- | --- | --- |
| activate | DRAFT/INACTIVE → ACTIVE | Exactly one current ACTIVE HEADQUARTERS and tenant Party LEGAL_ENTITY ACTIVE | company.activated.v1 |
| deactivate | ACTIVE → INACTIVE | Local | company.deactivated.v1 |
| archive | DRAFT/INACTIVE → ARCHIVED | Local | company.updated.v1 with ARCHIVED |

Each effective transition increments only Company exactly once and shares one UTC
microsecond instant/actor across row, accepted snapshot and enabled event. It
preserves legal identity, operational fields, creation audit and every Site/child.
Site operativity stays Company ACTIVE && Site ACTIVE; historical Site replay keeps
its original operativity. ARCHIVED rejects new ordinary mutations, including no-op.
PATCH cannot change Company status. A new activate on ACTIVE/deactivate on INACTIVE
is 409 without accepting a no-op or consuming a key. archive on ARCHIVED is terminal.

| Status | HU03 code(s) |
| --- | --- |
| 400 | company-id-invalid; if-match-required/duplicated/invalid; idempotency-key-duplicated/blank/too-long; request-body-invalid-json/must-be-object/contains-unknown-fields |
| 404 | company-not-found; party-reference-not-found |
| 409 | company-archived; company-already-in-requested-state; company-transition-not-allowed; legal-headquarters-required; active-headquarters-required; party-must-be-legal-entity; party-must-be-active; idempotency-key-already-used-with-different-request |
| 412 | aggregate-version-mismatch |
| 502 | dependency-response-invalid |
| 503 | dependency-unavailable |
| 500 | server-error (unexpected failure or unreadable historical material) |

Known errors have exactly status/code, e.g.
`{"status":409,"code":"active-headquarters-required"}`. Context and framework 415
retain the shared envelope. A validated Process-Id is returned for the current
attempt, including replay and errors. No parser/provider values or internal causes
are exposed. HU01/HU02/HU04/HU05 retain their published catalogs.

Precedence is input → accepted key/replay or key conflict → tenant-qualified Company
→ revision → terminality/redundancy/transition → current headquarters → Party result.
Party type precedes activity. Missing Company and foreign Company both return 404
without provider access. Headquarters CLOSED history is ignored; DRAFT/INACTIVE
still reserve the slot but fail activation. No address/contact prerequisite exists.

Activation performs protected preparation Company → headquarters, closes its
transaction/session, calls the existing minimal Party eligibility endpoint with
current headers and finite timeouts, then repeats all local checks in final
acceptance. A concurrent replay precedes even a failed Party response. Changes to
headquarters are reread even when Company.version is unchanged. Deactivate, archive
and replay are local and work with providers/broker unavailable. Later changes to
Party are outside distributed atomicity; HU03 does not synchronize automatically.

Keys use independent company.activate.v1/company.deactivate.v1/company.archive.v1
namespaces within tenant. Original Company and If-Match bind the HMAC intent;
actor/process/observations/outbox mode do not. Replay returns the original 200 and
operational snapshot after another cycle/archive/restart/key rotation; it never
requeries present state or produces another event. Different intent is 409 without
disclosing another snapshot. Without a key no durable replay is promised.

One configurable lifecycle wait budget covers lookup, HTTP, local locks/writes,
commit, cleanup and recovery. Recovery reads a completion only after confirmed
transaction finalization; it never repeats a write. Unresolved/uncertain completion
is 503 without a rollback claim. Company, optional completion and enabled event
commit/revert together. Disabled stores no event; stored-only/published store one
PENDING event and do not publish. See [persistence contract](../architecture/persistence-contract.md)
for strict snapshot metadata and retained historical keys/formats without TTL.
