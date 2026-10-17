# Company information management (HU02)

`PATCH /v1/companies/{companyId}` accepts an `application/json` object containing
only code, operatingName, description and logoUrl. Context requires one canonical
lowercase Tenant-Id and Process-Id UUID and one exact User-Id. If-Match is mandatory:
canonical decimal int64 0..9223372036854775807, without quotes, signs, internal spaces,
leading zeros or wildcard. HTTP optional outer whitespace (OWS) is excluded by
the protocol parser, as specified in RFC 9112 section 5.1: `If-Match:  0 ` has
the value `0`. Validation remains strict on the extracted value. Idempotency-Key is optional; supplied keys must be one
nonblank opaque string of at most 128 code points, preserved exactly.

```bash
curl -X PATCH "$BASE_URL/v1/companies/$COMPANY_ID" \
  -H "Tenant-Id: $TENANT_ID" -H "Process-Id: $PROCESS_ID" \
  -H "User-Id: $USER_ID" -H 'If-Match: 0' -H 'Idempotency-Key: correction-1' \
  -H 'Content-Type: application/json' \
  -d '{"operatingName":" compañía  ágil ","description":null}'
```

Omitted values remain unchanged. Null clears description/logoUrl, but is invalid
for code/operatingName. All non-null values must be strings without coercion.
Duplicate, unknown, immutable, lifecycle and audit properties are rejected.
Business text is stripped and uppercased with Locale.ROOT, then limited to
64/200/500 code points. Supplied blanks are invalid. Logo URL is absolute HTTP(S),
with a host, no outer whitespace, at most 1000 code points and exact content.

HTTP 200 `successful` returns operational Company with version and audit, omitting
Party and null optionals. No Party, Geography or RabbitMQ call occurs. POST/GET
retain HU01 enriched responses. `{}`, redundant clearing and canonical equality
are no-op: aggregate audit/version stay intact, and no event is written. With a
key even a no-op records a durable original operational snapshot; without a key
no completion is stored. Completion createdAt/createdBy describes acceptance,
which may differ from unchanged Company updatedAt/updatedBy.

Structural/header errors are always rejected before replay. Then an accepted key
is compared with its historical fingerprint policy and HMAC key and its original
result is replayed before current existence/version/state. Different intent is
409 resource-conflict. New operations resolve tenant ownership (404 not-found),
then version (412 aggregate-version-mismatch), then state (ARCHIVED gives 409,
even for no-op). DRAFT/ACTIVE/INACTIVE retain their state. Invalid input is 400,
unsupported media 415, corrupt historical material 500 server-error and unavailable
or unresolved outcome 503 dependency-unavailable. Errors use the shared envelope;
Process-Id belongs to the current attempt.

The fingerprint binds tenant, company.update.v1, Company ID, original If-Match and
present/null/canonical value for each field. Actor/process/current state are excluded.
`{}` differs from `{"description":null}` even if both would be no-op. A retry must
repeat original body, key and precondition; it reproduces original version, audit
and values after later edits, archival, restart, key rotation or outbox mode change.
Keep historical keys and codecs with no TTL. Never assign an old hash the active key.

A tenant-qualified PostgreSQL row lock protects acceptance including no-op; one
explicit version-conditioned UPDATE increments effective changes exactly once.
Creation audit, identity, status and Sites remain intact. Company, optional
completion and enabled company.updated.v1 event commit or roll back together.
Code uniqueness is tenant scoped including ARCHIVED; own code is never a conflict.
The six application tables and HU01 migrations/snapshots remain compatible.

One positive finite `company-registry.update.wait-budget` (environment
`COMPANY_UPDATE_WAIT_BUDGET`, default 10s) covers local lookup, lock, write, session
cleanup and durable recovery. Remaining time is applied to server statement/lock
timeouts. After rollback or uncertain commit with a key, a new session resolves the
durable completion before returning replay/conflict/503. No acceptance is inferred
from matching current state or own Party. Without a key an uncertain commit returns
503 and cannot promise original-result replay; the command is never blindly retried.

Outbox disabled omits events; stored-only/published persist one PENDING event only
for effective changes. Publication, lifecycle endpoints and delivery remain HU03/HU08.
Rollback deployment retains V3 and accepted data; restore compatible code or suspend
PATCH, without deleting completions or historical secrets.
