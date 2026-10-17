# External reference contract provenance

## Party Registry

Provider revision inspected:
[`fd603dd08fd980b96b5f35056345d638e4080bb2`](https://github.com/astudilloalex/party-registry-service/tree/fd603dd08fd980b96b5f35056345d638e4080bb2).
`GET /v1/parties/{partyId}` accepts Tenant-Id/User-Id/Process-Id and returns
`status`, `code=successful`, `data.partyId`, `data.type`, `data.recordStatus`.
The DTO is `api/model/response/PartyDetailResponse.java`; Party lifecycle values
are DRAFT/ACTIVE/INACTIVE/ARCHIVED. Types are NATURAL_PERSON/LEGAL_ENTITY.
The minimal validation port keeps legal details/display names/identifiers out of its projection.
HU01 separately uses `PartyReads` to compose the safe public read model described below.
No tenant field is invented in the provider DTO: tenant scope is in the request.

The neutral `PartyReference` distinguishes absence (null), incompatible type
(`legalEntity=false`) and lifecycle/activity. Registration and activation policies
are separate: existence/type validation does not imply ACTIVE is always required.

## Geographic Reference

Postman collection
[`15834347-d3591c82-bd52-46a9-973d-a7a102d4b9b3`](https://go.postman.co/collection/15834347-d3591c82-bd52-46a9-973d-a7a102d4b9b3)
was re-read on 2026-10-16. It confirms:

- Country request `ed5c0238-71ab-455a-32b8-e63856198cb5`:
  `GET /api/v1/countries/by-alpha2/{alpha2Code}`.
- Division request `feaec323-d194-401e-9891-bf97b90a088f`:
  `GET /api/v1/countries/{countryId}/administrative-divisions/by-canonical-code/{canonicalCode}`.
- Both declare process-id/user-id and optional company-id. Company sends its
  validated process and actor exactly. **No tenant→company conversion** is made;
  neither company-id nor an undocumented Tenant-Id is sent to this provider.
- Postman still has no saved response examples; route/header evidence alone is
  not a complete response contract.

The response gap is now confirmed from a clean provider checkout at
[`fcf309e265b4b16c6ee664fcc6dec3a5380450e2`](https://github.com/astudilloalex/geographic-reference-service/tree/fcf309e265b4b16c6ee664fcc6dec3a5380450e2):

- `api/dto/CountryApiResponse.java`: `id`, `alpha2Code`, `status` (plus fields not
  required by Company). The `successful` envelope is produced by ResponseManager.
- `api/dto/AdministrativeDivisionApiResponse.java`: `id`, `countryId`,
  `canonicalCode`, `status` (plus unneeded fields).
- `api/resource/AdministrativeDivisionResource.findByCanonicalCode` checks the
  country exists, resolves by country/code, returns 404 when absent, and wraps
  the declared DTO. `GetAdministrativeDivisionQueryService` and the country-scoped
  repository confirm the lookup semantics.
- `domain/model/enums/GeographicRecordStatus`: DRAFT/ACTIVE/DEPRECATED/RETIRED.
  A lookup does not silently filter to ACTIVE: activity remains explicit in the
  neutral projection and is checked by future workflows.
- `api/logging/MDCRequestFilter` confirms supported headers and optional company
  context. The provider may normalize its response correlation UUID casing;
  Company preserves the outgoing validated Process-Id rather than replacing it.

This evidence permits the division adapter: resolve alpha-2 to country ID, then
check returned division.countryId and canonicalCode against that request. No
provider database access is allowed, and no placeholder permissive lookup is used.
Future provider drift must fail as an incompatible contract, not a valid reference.

## Neutral I/O contracts

Application owns Party/Country/Division reference ports and minimal projections.
404 is confirmed absence; successful responses require matching identity/code,
known type/status, data object and matching envelope/HTTP status. Dependency
timeout/unavailability/invalid contract are distinct `ReferenceFailure` kinds;
none is turned into absence or acceptance. HTTP/client DTOs remain Infrastructure
details. URLs and finite connection/read timeouts are externally configurable,
with no calls at application boot and no generic retries.

## JVM adapter evidence

`PartyReferenceAdapterTest`, `CountryReferenceAdapterTest`,
`DivisionReferenceAdapterTest` and `ReferenceFailureTest` execute actual reactive
REST clients against a real HTTP stub. They verify routes/headers, both provider
enum sets, minimal projections, tenant scope, wrong IDs/codes/country ownership,
absent references, inactive country/division, malformed JSON, invalid success
envelopes, real read timeouts, 503 and refused network connections. Error bodies
are classified by HTTP status before attempting success DTO coercion; every
response is closed. Typed client/serialization/connection causes are classified
without broad business-failure recovery or exposing provider messages/causes.

`OfflineStartupTest` launches with both providers at `http://127.0.0.1:9` and
checks readiness/OpenAPI. The clients perform no bootstrap lookups. See README
for URL/timeouts; every configured connect/read timeout is validated at startup
as positive finite milliseconds. Native-process evidence remains section 11.

## HU01 complete public enrichment

The same inspected provider revision's `PartyDetailResponse`,
`LegalEntityDetailsResponse`, `PartyIdentifierResponse` and
`PartyIdentifierResource` establish the read contract. Detail supplies identity,
type, displayName, recordStatus, version and all four audit fields. Legal details
supply legalName/incorporationCountryCode plus optional tradeName/legalFormCode,
incorporatedOn/dissolvedOn. `GET /v1/parties/{partyId}/identifiers` supplies a
shared top-level paginated envelope. Company follows every nextCursor at constant
limit 200, preserving provider order and all five historical statuses:
PENDING_VERIFICATION, VERIFIED, REJECTED, EXPIRED, REVOKED. It does not use the
separate current-identifier projection of the type-specific Party endpoints.

Each safe identifier includes IDs, schemeCode, maskedValue, status, isPrimary,
version, creation/update dates and optional issuerCode/issuedOn/expiresOn/
verifiedAt/verifiedBy. No decryption or full identifier endpoint is used. Every
page receives the same canonical Tenant-Id/Process-Id and exact User-Id. The
adapter validates ownership, scalar types, HTTP/envelope agreement, pagination
counts, known states, duplicate IDs and repeated cursors. Confirmed absence is
separate from unavailable/invalid dependencies. A missing identifier collection
is never invented as an empty success.

`company-registry.enrichment.deadline` defaults to 30s for the entire operation,
including queued work and every page; `company-registry.enrichment.concurrency`
defaults to 4 (range 1–200). Per-client finite connection/read timeouts remain in
force. Each company's pages are sequential, and list results retain input order.
Deadline or caller cancellation cancels outstanding reactive HTTP subscriptions.
No calls occur at bootstrap, no generic HTTP retry is installed and no broker is
required. Native execution is verified separately from the JVM adapter tests.
