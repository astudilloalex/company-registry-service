# Planned business operations and shared conventions

The source [OpenAPI](company-registry.openapi.yaml) publishes the implemented HU01/HU02/HU04 operations:
`POST /v1/companies`, `GET /v1/companies/{companyId}`, `GET /v1/companies` and
`PATCH /v1/companies/{companyId}`, `POST /v1/companies/{companyId}/sites`,
`GET /v1/companies/{companyId}/sites/{siteId}` and `GET /v1/companies/{companyId}/sites`.
See [HU04 contract](company-site-registration-and-retrieval.md) for local Site reads,
replay, canonical filters and specific errors. See [HU02 contract](company-information-management.md)
for runnable modification/cleaning and replay rules.
The [HU01 acceptance record](../operations/hu01-acceptance-review.md) records
executed JVM, native and image verification; contract publication alone is not evidence. All require exactly one
Tenant-Id and Process-Id as lowercase canonical UUIDs, plus an exact User-Id.
The inventory links other planned behavior to the actual issue titles:

| HU / issue | Planned behavior | Mutation conventions |
| --- | --- | --- |
| [HU01 / #3](https://github.com/astudilloalex/company-registry-service/issues/3) | Company registration and retrieval/listing | Registration requires Idempotency-Key; initial Company version 0. Reads require no mutation headers. |
| [HU02 / #4](https://github.com/astudilloalex/company-registry-service/issues/4) | Company information management | PATCH operational fields; mandatory decimal If-Match, optional durable key, local 200 without Party. |
| [HU03 / #5](https://github.com/astudilloalex/company-registry-service/issues/5) | Company lifecycle management | Expected Company version; activation preconditions under Company→Site lock order. |
| [HU04 / #6](https://github.com/astudilloalex/company-registry-service/issues/6) | Site registration and retrieval/listing | Mandatory Idempotency-Key, no If-Match; Site DRAFT/version 0; historical local reads. |
| [HU05 / #7](https://github.com/astudilloalex/company-registry-service/issues/7) | Site lifecycle management | Expected Site version; CLOSED is terminal. |
| [HU06 / #8](https://github.com/astudilloalex/company-registry-service/issues/8) | Site address management | Expected Site version; geographic validation requires confirmed provider contracts. |
| [HU07 / #9](https://github.com/astudilloalex/company-registry-service/issues/9) | Site contact management | Expected Site version; contact canonicalization precedes validation/fingerprinting. |

## Idempotency

Exactly one nonblank opaque `Idempotency-Key`, max 128 characters, preserved
exactly. It is mandatory for Company registration and replay. Other mutation
contracts specify whether it applies; T00 does not silently require it on reads.
The completion key is `(tenant, operation, key)`. Use the canonical effective
input, including field-specific normalization and opaque-value preservation, for
the configured HMAC SHA-256 fingerprint. T00 startup does not require an HMAC
secret for an unimplemented workflow.

Matching replays return the original accepted status and schema-versioned JSON
snapshot without requerying the current aggregate and without incrementing its
version. Same key/different effective content is HTTP 409; a stale expected
version is HTTP 412. Aggregate mutation, completion and enabled outbox persist
atomically. Registration/replay orchestration belongs to the owning HU.

## Expected version

The Party-compatible internal `If-Match` is decimal `0|[1-9][0-9]*` in signed
int64, not an HTTP entity-tag: no quotes, wildcard, sign or leading zeros. Validation applies to the extracted HTTP field value; outer protocol OWS is
excluded according to RFC 9112 section 5.1. Internal whitespace remains invalid. Missing
or invalid input is 400, stale version is 412. Company uses Company.version;
Site/Address/Contact use Site.version. Actual changes increment once; replay and
no-op do not. Company and Site versions are not coupled, and neither is the
outbox's technical delivery version.

## Cursor pagination

`cursor` is opaque; `limit` is 1–200 inclusive, default 50. The order is
`created_at DESC, id DESC`, and the cursor binds tenant, filters and order.
Company filters are exact `code`, `partyId`, `status`. Reject unknown or repeated
parameters and invalid/incompatible cursors with 400. Do not promise a frozen
snapshot across concurrent requests. Codecs/queries and Site-specific filters
are implemented by HU01/HU04, not by this foundation document.

The **published shared library**, `api-response-quarkus-errors:1.0.0-SNAPSHOT`,
provides `ApiResponse`, `ResponseManager` and `PaginationMetadata`. Its pagination
fields are **top-level** `nextCursor`, `prevCursor`, `totalElements`, `totalPages`,
`numberOfElements`; the three counters are nullable nonnegative int32, and null
fields are omitted. `data` is a list, including an empty list for no results.
There is no invented nested `pagination` object. Standard success code is
`successful`. `PaginationLibraryContractTest` checks these actual runtime types
and serialized field names, alongside canonical decimal boundaries.

## Error ownership

Company-specific codes and known-failure translation belong to the service's
API layer. Owned request DTOs opt into `@CompanyJsonBody`; the reader interceptor
translates input-binding failures to the service's `bad-request`, preserving
unexpected definition/I/O failures. The library creates the final error envelope.
Bean Validation preserves stable field codes under `validation-error`; unexpected
failures are sanitized `server-error`. See
[HTTP evidence](../architecture/http-foundation-verification.md).
