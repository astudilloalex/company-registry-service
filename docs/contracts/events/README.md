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
