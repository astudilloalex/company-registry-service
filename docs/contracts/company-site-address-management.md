# HU06 — Company Site address management

All five routes require Tenant-Id, User-Id and Process-Id. Address ownership is
tenant/Company/Site; an address has no independent revision. Mutations require
the original decimal Site If-Match. POST additionally requires Idempotency-Key;
PATCH and DELETE accept an optional key. GET requires neither mutation header.

| Method | Path below `/v1/companies/{companyId}/sites/{siteId}` | Result |
| --- | --- | --- |
| POST | `/addresses` | 201 accepted address |
| GET | `/addresses/{addressId}` | 200 current nondeleted address |
| GET | `/addresses` | 200 current page |
| PATCH | `/addresses/{addressId}` | 200 accepted address, including no-op |
| DELETE | `/addresses/{addressId}` | 200 original deletion receipt |

## Create and correct

```json
{"addressType":"PHYSICAL","countryCode":"EC","addressLine1":"AVENIDA  JOSÉ","latitude":-0.123456,"longitude":78.123456,"isPrimary":true}
```

The three text fields are explicit requirements. Optional division, second line,
house/postal number and coordinates default null; isPrimary defaults false.
Text uses strip and Locale.ROOT uppercase, preserving accents and internal spaces.
Canonical limits are lines 300, division 64, house/postal numbers 32 Unicode code
points; country must be alpha-2. Supplied optional blanks are rejected.

```json
{"houseNumber":"12A","administrativeDivisionCode":null,"latitude":null,"longitude":null}
```

PATCH preserves omitted fields. Explicit null clears optional text and both
coordinates; required text/type/primary cannot be null. Coordinates are an
indivisible pair, latitude [-90,90], longitude [-180,180], exactly representable
with six decimals. Extra trailing zeros are accepted; no rounding or string
coercion is allowed. Canonical equality, including `{}`, is a no-op that still
checks current ownership, revision and editability.

New geographic assignments require ACTIVE country/division from the verified
provider. Changing country retains an omitted division and validates the resulting
combination. Null explicitly removes it. Retaining references or removing only
division does not call Geografía; corrections, GET, DELETE, replay and no-op
remain local when the provider is unavailable.

## Primaries, audit and deletion

Primary selection is explicit per PHYSICAL/MAILING type, with zero allowed.
Selecting or reclassifying a primary atomically demotes the destination primary
first. Omitting isPrimary preserves it; false does not displace the destination.
Removing/demoting/reclassifying a primary never picks a replacement for its old
type. Every affected address and Site share one UTC microsecond timestamp and
exact actor, with creation audit retained. An effective acceptance increments
Site once and stores one enabled `company.site.updated.v1`; Company is unchanged.

Address responses contain exactly the eleven required fields addressId, siteId,
siteVersion, addressType, countryCode, addressLine1, isPrimary and the four audit
fields; six optional nonnull fields may be added. Coordinates remain JSON numbers.
GET returns the current shared Site revision; replay returns the original revision.

DELETE accepts an absent body or `{}` and retains the historical row. Its `data`
contains only addressId, siteId, siteVersion, deletedAt and deletedBy. A new
request against a deleted address is 404; replay keeps the original 200 receipt.

## Paging

`GET .../addresses?addressType=PHYSICAL&isPrimary=true&limit=50` uses exact filters
combined with AND. Only addressType/isPrimary/limit/cursor are admitted, once each.
Limit is 1–200, default 50. Ordering is createdAt DESC/addressId DESC. The top-level
envelope contains data array and numberOfElements, with nextCursor only for
continuation. It has no totals or nested pagination. A cursor authenticates its
collection, tenant/Company/Site, filters, order and boundary; actor/process/limit
can change. Each page observes current values; no snapshot across pages is promised.

## Replay, precedence and errors

HU06 namespaces are `company.site.address.{create,update,delete}.v1`. Matching
intent includes canonical fields, PATCH presence, hierarchy/address and original
If-Match. Replay precedes current data/providers and survives deletion, closing,
archival, rotation and restart. It returns original status/audit/revision with
the current Process-Id. Another intent using the same tenant/operation/key is 409.
No-op and replay add no business revision or event. Retain acceptances and historic
keys without TTL; see the persistence contract for policy/format version 1.

Precedence is input → replay/conflict → Company/Site/address absence → Site revision
→ Company ARCHIVED → Site CLOSED → local policy → geographic observation. HTTP
runs outside database locks, then final protection repeats these checks. Thus a
concurrent deleted address is 404 and a surviving address with stale revision is
412 before remote failure. Preparation does not authorize a later write.

| Status | Descriptive codes |
| --- | --- |
| 400 | Shared context/header errors; company-id-invalid/site-id-invalid/address-id-invalid; request-body-required/invalid-json/must-be-object/contains-unknown-fields/contains-duplicate-fields; per-field required/must-be-string/blank/too-long; address-type-not-supported; address-country-code-invalid; address-is-primary-must-be-boolean; coordinate pair/type/range/precision errors; query-parameter-not-supported/repeated; address-is-primary-filter-invalid; address-page-limit-must-be-an-integer/between-1-and-200; address-cursor-invalid-or-incompatible |
| 404 | company-not-found, site-not-found, address-not-found, country-reference-not-found, administrative-division-reference-not-found |
| 409 | site-mutation-not-allowed-for-archived-company, site-closed, country-must-be-active, administrative-division-must-be-active, idempotency-key-already-used-with-different-request |
| 412 | aggregate-version-mismatch |
| 415 | unsupported-media-type |
| 502 | dependency-response-invalid |
| 503 | dependency-unavailable, including unknown durable finalization |
| 500 | server-error, including unsupported/corrupt historical material |

Errors contain only status/code and valid Process-Id response header. They omit
submitted values, provider payloads and internal diagnostics. OpenAPI is the
machine-readable catalog. A finite total wait budget includes HTTP, locks,
commit/close and recovery. Unknown finalization never triggers another mutation;
the client can retry the same keyed intention to resolve its durable acceptance.
