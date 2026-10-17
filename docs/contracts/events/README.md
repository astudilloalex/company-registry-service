# company.created.v1 handoff to HU08

The schema and example describe exactly eleven envelope fields and seven payload
fields. Producer checks additionally require companyId=aggregateId,
createdAt=occurredAt, a distinct UUIDv7 eventId, aggregateVersion 0 and schema
version 1. causationId is explicitly null for HTTP creation. Party enrichment,
masked identifiers, description, logo URL and idempotency material are excluded.

HU01 stores the seven-field payload and original envelope metadata in outbox,
atomically with Company and completion. disabled omits it; stored-only and
published both store PENDING, zero attempts and technical delivery version 0.
Neither mode activates a broker connection or a publisher. Replays never store
another event. HU08 must serialize the stored original fact (including explicit
null causation), preserve eventId/content through redelivery, and route to
exchange `company.events` with routing key `company.created.v1`. The current
foundation default `company.events` for the routing key must be aligned in HU08;
it is not used to publish in HU01. Delivery will be at least once with no global
order or exactly-once guarantee. Incompatible changes require a new version.

The Gradle resource task embeds both JSON files under
`META-INF/contracts/events/`; the native resource inclusion retains them for
external validation against the real process. `CompanyCreatedContractTest`
validates the example and produced event with Draft 2020-12 and rejects extra
payload fields. Schema validation belongs to the external test harness; the
runtime candidate and mapper enforce the cross-field invariants. JVM resource
checks do not substitute for native execution evidence.

## company.updated.v1

HU02 records effective operational changes in the same local transaction as
Company and its optional accepted completion. The eleven-field envelope and
seven-field payload (`companyId`, `partyId`, `code`, `operatingName`, `status`,
`updatedAt`, `updatedBy`) describe the resulting revision. `companyId` equals
`aggregateId`; `updatedAt` equals `occurredAt`; the actor is original User-Id.
HTTP causation is explicit null. Description, URL and legal Party data are excluded.
No-op and replay never create another event. `disabled` omits storage;
`stored-only` and `published` store PENDING with zero attempts/technical version.
No mode publishes or requires RabbitMQ in HU02.

The schema permits ARCHIVED for the HU03 archival producer; HU02 emits only
DRAFT/ACTIVE/INACTIVE and does not implement archival. HU08 will deliver to
exchange `company.events`, routing key `company.updated.v1`, using the original
stored snapshot and eventId even after later Company changes. Consumers deduplicate
by eventId and reason about aggregateVersion; no global order or exactly-once
promise is made. Claims, leases, confirms and delivery retries remain HU08 work.
Schema and example are packaged under `META-INF/contracts/events/` in JVM/native.
