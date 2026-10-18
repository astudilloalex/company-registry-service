# Company Site information and lifecycle (HU05)

All routes require the existing trusted Tenant-Id/User-Id/Process-Id context,
Company/Site UUIDs and one canonical decimal Site `If-Match`. Idempotency-Key is
optional, opaque, nonblank and at most 128 Unicode code points. Success is 200
`successful` with the same twelve Site fields as HU04. Process-Id belongs to the
current request. No remote Party, Geography or RabbitMQ call is made.

| Route under `/v1/companies/{companyId}/sites/{siteId}` | Body | Allowed effect |
| --- | --- | --- |
| PATCH | Required object with only code/name/siteType | Partial information; omission preserves, null rejects |
| POST `/activate` | Absent or `{}` | DRAFT/INACTIVE → ACTIVE |
| POST `/deactivate` | Absent or `{}` | ACTIVE → INACTIVE |
| POST `/close` | Absent or `{}` | DRAFT/ACTIVE/INACTIVE → CLOSED |

All documents are consumed completely. Unknown/duplicate properties, non-object
roots, malformed JSON and trailing tokens are rejected. Commands accept no fields.
Code/name use strip and Locale.ROOT uppercase before limits of 64/200 code points;
accents and internal spaces remain significant. siteType is an exact enum. PATCH
never applies HU04's BRANCH default when siteType is omitted.

For example, PATCH `{"name":" compañía  ágil "}` with If-Match 0 preserves a
HEADQUARTERS classification, returns `COMPAÑÍA  ÁGIL`, and advances Site once when
the name changes. A following PATCH `{}` with If-Match 1 returns the same version
and update audit. `SiteMutationHttpTest` exercises these requests and the four
routes. These examples do not imply concurrent or native acceptance evidence.

Ownership precedes revision, then Company ARCHIVED, Site CLOSED, command eligibility
and headquarters/code invariants. Stale revision is 412 even for no-op/terminal
resources. ARCHIVED blocks every new mutation; CLOSED is terminal. New activate of
ACTIVE or deactivate of INACTIVE is 409, while an equivalent durable replay returns
its original success. An ACTIVE Company cannot lose, deactivate, close or reclassify
its ACTIVE legal HEADQUARTERS. At most one non-CLOSED HEADQUARTERS exists, including
DRAFT/INACTIVE reservations. Only Site revision/audit changes; Company is locked but
not mutated. Company lifecycle endpoints remain HU03 work.

Code A → B frees A only at commit. A rollback retains A. CLOSED retains its current
code even though closing HEADQUARTERS releases the legal-headquarters reservation.
No historical aliases or atomic headquarters replacement are added.

Each operation has its own namespace: company.site.update.v1,
company.site.activate.v1, company.site.deactivate.v1, company.site.close.v1. Identity
is tenant/operation/key, while Company/Site/original If-Match and canonical intent
with presence are signed. `{}` and an explicit canonically unchanged name are
different PATCH intentions. Missing command body and `{}` are equivalent. Only
successful acceptances consume keys, including keyed PATCH no-op. A conflicting
accepted key returns 409 before current ownership/revision/state checks.

Replay returns original data, audit, revision and operativity, even after later
Site changes/closure or Company archival. New acceptance computes operativity under
Company → Site locks. GET computes current Company ACTIVE && Site ACTIVE; therefore
GET and an old replay may disagree at the same Site revision. Actor/process changes
do not alter intent; the event and accepted audit keep their original values.

Site change, optional completion and enabled event commit or roll back together.
Effective PATCH/activate/deactivate store updated; close stores only closed. No-op
and replay store no event. Disabled mode omits outbox; stored-only/published store
PENDING without publishing. Refer to the event schemas and examples in `events/`.

Known errors contain exactly status/code. Body/header errors are 400, scoped absence
404, state/code/headquarters/key conflicts 409, stale version 412 and unsupported
media 415. Unexpected/corrupt historical material is sanitized 500. Unavailability
or an unresolved transaction result is 503. Recovery uses a fresh session only after
confirmed finalization, with one configured finite budget and no automatic write
retry. Without a key, an uncertain commit cannot reproduce an original acceptance.
