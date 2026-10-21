# Site contact management (HU07)

Contacts belong to tenant/Company/Site. The public collection is
`/v1/companies/{companyId}/sites/{siteId}/contacts`; individual paths append
`/{contactId}`. POST returns 201; GET/PATCH/DELETE return 200. Every request
uses Tenant-Id, User-Id and Process-Id. Mutations require the decimal canonical
nonnegative int64 Site If-Match. POST requires an opaque Idempotency-Key of at
most 128 code points; PATCH/DELETE accept omission. GET requires neither.

## Values and partial changes

POST requires contactType and value, defaults label null and isPrimary false.
Only EMAIL, PHONE, MOBILE and WHATSAPP are supported after strip/uppercase.
EMAIL V1 requires ASCII before full Locale.ROOT lowercase, dot-atom-text local
part of at most 64 characters and total length at most 254. Domains have at
least two labels of 1–63 ASCII alphanumeric characters with interior hyphens.
No quoted addresses, lists, display names, comments or IP literals are accepted.
Dots and +aliases are preserved; no provider-specific equivalence is applied.
**This approved contract replaces T00/HU07 AC07 local-part case preservation.**
` Ventas@Example.COM ` becomes `ventas@example.com`.

Phone channels require explicit +, admit ASCII digits, spaces, parentheses and
hyphens, validate using pinned libphonenumber 9.0.41 and store E.164 (≤15 digits).
MOBILE accepts MOBILE/FIXED_LINE_OR_MOBILE. Exclusively fixed numbers may be
PHONE/WHATSAPP, but fail MOBILE. No country is inferred, extensions are rejected
and no availability, ownership, DNS, SMTP or messaging calls occur.

Label uses strip/Locale.ROOT uppercase and at most 80 canonical Unicode code
points, including uppercase expansion. PATCH omission preserves any field;
label null clears it, while supplied blanks fail. Type/value null and primary
null fail. PATCH permits reclassification while preserving contactId. The
resulting value must satisfy the resulting channel; supplying only value is
supported. Equal canonical results, including {}, are no-ops.

Uniqueness is live Site/type/canonical value. The same number can coexist under
PHONE/MOBILE/WHATSAPP. A duplicate returns 409 before modifying primaries.
Each category permits zero or one explicit primary. Selecting a new primary
atomically demotes the old one. Omitted primary retains selection, including
reclassification; explicit false leaves the destination primary intact.
Deleting/demoting never chooses an automatic replacement for the old category.

## Revision, state, audit and replay

Each effective contact change advances Site once; Contact has no version.
All affected contacts and Site share microsecond UTC updatedAt and exact
updatedBy. Identity/creation remain intact and Company data/version/audit are
unchanged. Company and Site DRAFT/ACTIVE/INACTIVE permit editing. ARCHIVED
Company or CLOSED Site reject new writes, including no-op, after absence and
revision checks. GET continues to expose live contacts in terminal states.

Acceptance protects Company then Site. Contacts, Site, optional completion and
enabled minimal Site event commit or roll back together. Disabled outbox skips
an event; stored-only/published retain one PENDING event, without a broker call.
Payload contains exactly siteId, companyId, code, name, siteType, status,
updatedAt and updatedBy, never contactId/value/label.

Namespaces are company.site.contact.{create,update,delete}.v1. Identity is
tenant/operation/key; fingerprints include complete hierarchy, route contact,
original If-Match, policy 1 and canonical intention. They exclude actor, process
and key. POST materializes defaults; PATCH presence remains independent and
value signs its lexical EMAIL/PHONE family without reading a current contact.

An equivalent retry returns original HTTP status, data, siteVersion and audit
with current Process-Id, after later modifications, reclassification, deletion,
closure, archival, restart or key rotation. Different intent returns 409 without
exposing another snapshot. No-op with key stores its original result; without
key it stores no completion. Durable formats and historical keys have no TTL.
Missing historical material returns sanitized 500; it is never rebuilt from GET.

`COMPANY_SITE_CONTACT_WAIT_BUDGET` / `company-registry.site-contact.wait-budget`
defaults to 10s and must convert to positive finite nanoseconds at startup.
The total budget includes reads, locks, commit/close and recovery. Recovery reads
only after confirmed transaction finalization and uses remaining time; no second
write is attempted. Uncertain finalization or exhaustion returns 503.

DELETE accepts no body or {} and performs logical removal with isPrimary false.
Data contains exactly contactId, siteId, siteVersion, deletedAt and deletedBy.
A fresh DELETE after removal returns 404; replay returns the original receipt.
The historical row remains and the live value becomes reusable.

## Current reads and cursor pages

Detail and each page read contacts with Site revision in the same qualified
statement. All page items share that revision; another child may advance it
without changing contact audit. Replay retains the original accepted revision.
An empty collection returns data [] and numberOfElements 0; GET Site supplies
its revision when no contact exists.

Allowed query parameters occur once: contactType (exact enum), isPrimary
(exact true/false), limit and cursor. Filters combine with AND before limit+1.
Order is createdAt DESC/contactId DESC. Limit defaults to 50 and accepts 1–200.
Envelope contains data array and numberOfElements; nextCursor appears only
with continuation. There are no totals or nested pagination. The HMAC cursor
binds contacts collection, format/key, tenant/Company/Site, filters, order and
boundary. Other collections, scopes, filters or tampering fail with 400.
Actor, process and allowed limit may change. Pages read current values and do
not promise a temporal snapshot across requests.

## Executable examples

Set BASE, TENANT, COMPANY, SITE, PROCESS and CONTACT to valid values; VERSION
is the latest Site revision. Capture returned contactId/siteVersion before the
next command. Every command below includes required business context.

```bash
CONTACTS="$BASE/v1/companies/$COMPANY/sites/$SITE/contacts"
curl -X POST "$CONTACTS" -H "Tenant-Id: $TENANT" -H 'User-Id: Actor-aB' \
  -H "Process-Id: $PROCESS" -H "If-Match: $VERSION" -H 'Idempotency-Key: create-email' \
  -H 'Content-Type: application/json' --data '{"contactType":"email","value":" Ventas@Example.COM ","label":"ventas"}'
curl -X PATCH "$CONTACTS/$CONTACT" -H "Tenant-Id: $TENANT" -H 'User-Id: Actor-aB' \
  -H "Process-Id: $PROCESS" -H "If-Match: $VERSION" -H 'Idempotency-Key: value-only' \
  -H 'Content-Type: application/json' --data '{"value":"nueva@example.com"}'
# For an existing primary MOBILE, preserve selection while moving to WHATSAPP:
curl -X PATCH "$CONTACTS/$CONTACT" -H "Tenant-Id: $TENANT" -H 'User-Id: Actor-aB' \
  -H "Process-Id: $PROCESS" -H "If-Match: $VERSION" -H 'Content-Type: application/json' \
  --data '{"contactType":"WHATSAPP"}'
curl "$CONTACTS?contactType=EMAIL&isPrimary=false&limit=50" \
  -H "Tenant-Id: $TENANT" -H 'User-Id: Actor-aB' -H "Process-Id: $PROCESS"
curl -X DELETE "$CONTACTS/$CONTACT" -H "Tenant-Id: $TENANT" -H 'User-Id: Actor-aB' \
  -H "Process-Id: $PROCESS" -H "If-Match: $VERSION" -H 'Idempotency-Key: delete-contact'
# Replay DELETE with exactly the original VERSION and key; preserve those variables.
```

Validation precedence is headers/routes, complete JSON/tokens, root object,
unknowns, duplicates, all structural field types/required, then selected-policy
canonization in contactType/value/label/isPrimary order. Replay/conflict follows;
a new attempt then checks Company/Site/contact absence, revision, Company state,
Site state and effective rules. Errors contain only status/code and valid
Process-Id header. See ContactErrorResponse in the published OpenAPI for the
closed catalogue, including 400 field/query errors, 404 ownership, 412 revision,
409 state/value/key conflict, sanitized 500 and 503 uncertainty.
