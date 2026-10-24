# Company Registry event contracts

HU01–HU07 are the producers; HU08 delivers their committed original facts.
The catalog remains exactly seven event types, all schema 1, with unchanged
payloads and schemas. Archived Company uses updated; Address/Contact changes
use Site updated. The default exchange is `company.events`; routing is eventType
via `%s`, or the configured template such as `integration.%s`.

Redelivery preserves eventId, tenant, correlation, explicit null causation,
aggregateVersion, timestamps and snapshot. Delivery metadata never enters the
eleven-field envelope. Consumers deduplicate by eventId and handle gaps or
reordering by aggregateVersion: delivery may be out of order within one aggregate.
PUBLISHED means broker confirmation, including a possible unroutable publication;
prepare queues and bindings before enabling delivery. See
[HU08 delivery contract](../outbox-publication.md).

The producer notes below describe each producer's original acceptance behavior;
current delivery semantics are controlled by HU08 and the selected runtime mode.

## HU03 lifecycle facts

company.activated.v1 and company.deactivated.v1 use the same eleven-field envelope
and seven-field operational update payload, constrained to ACTIVE and INACTIVE.
Archival uses company.updated.v1 with ARCHIVED and the existing V1 schema; its
separate archived example is packaged too. Producers enforce companyId=aggregateId,
updatedAt=occurredAt, positive resulting Company revision, a distinct UUIDv7 eventId,
original tenant/actor/process and explicit null causationId. Events contain no Party
projection, Site, description/logo or idempotency material. Technical attempts and
version start at zero in PENDING. There are no delivery timestamps/errors initially.

HU08 delivers each original stored fact to exchange `company.events`, routing key
equal to eventType (`company.activated.v1`, `company.deactivated.v1`,
`company.updated.v1`). Keep eventId on redelivery; do not rebuild from current state.
There is no company.archived.v1. At-least-once delivery and no global ordering remain
the delivery contract. HU08 owns claims/leases/scheduler/confirms/retries. HU03
requires no broker in any mode, stores none in disabled and one applicable event in
stored-only/published, atomically even without an idempotency key.

The schema and example describe exactly eleven envelope fields and seven payload
fields. Producer checks additionally require companyId=aggregateId,
createdAt=occurredAt, a distinct UUIDv7 eventId, aggregateVersion 0 and schema
version 1. causationId is explicitly null for HTTP creation. Party enrichment,
masked identifiers, description, logo URL and idempotency material are excluded.

HU01 stores the seven-field payload and original envelope metadata in outbox,
atomically with Company and completion. disabled omits it; stored-only and
published both store PENDING, zero attempts and technical delivery version 0.
Only published activates delivery after the business commit. Replays never store
another event. HU08 must serialize the stored original fact (including explicit
null causation), preserve eventId/content through redelivery, and route to
exchange `company.events` with routing key `company.created.v1`. The routing template defaults to `%s`; migrate any former fixed override before activation. Delivery will be at least once with no global
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
promise is made. HU08 owns claims, leases, confirms and delivery retries.
Schema and example are packaged under `META-INF/contracts/events/` in JVM/native.
# company.site.created.v1

HU04 stores an immutable Site fact with the eleven-field envelope and closed
eight-field payload: siteId, companyId, code, name, siteType, status, createdAt,
createdBy. aggregateType is COMPANY_SITE, aggregateId equals siteId, revision is
0, schema is 1, createdAt equals occurredAt, and HTTP causation is explicit null.
The event UUIDv7 differs from Site's UUIDv7. EffectivelyOperational is excluded.

Disabled stores no event; stored-only/published store one PENDING event with zero
attempts and technical version. Site, acceptance and enabled event commit together.
HU04 has no publisher or broker connection. HU08 will use exchange company.events
and routing key company.site.created.v1, delivering the stored original fact with
the same eventId after Site/Company changes. Delivery is at least once without
global ordering or exactly-once guarantees; consumers may deduplicate by eventId.
Schedulers, claims, leases, confirms and delivery retries belong to HU08.
Schema/example are packaged under META-INF/contracts/events for JVM/native.

## HU05 Site mutation handoff

`company.site.updated.v1` and `company.site.closed.v1` use exchange `company.events`
and their event type as routing key. Payload has eight fields; the envelope has
eleven. Close emits only closed; effective PATCH/activation/deactivation emit updated.
No-op/replay emit neither. Original event identity, context, revision and audit stay
unchanged across later aggregate changes, mode changes and at-least-once redelivery.
HU08 must deliver the stored fact; there is no global ordering or exactly-once promise.
The child example shows a later Site revision whose basic fields stay unchanged;
HU06/HU07 details require a qualified query and do not enter this payload.

HU07 contact creation, effective PATCH and logical deletion reuse
`company.site.updated.v1`. A joint primary replacement still emits one event at
the resulting shared Site revision. Its closed payload has exactly `siteId`,
`companyId`, `code`, `name`, `siteType`, `status`, `updatedAt`, `updatedBy`.
No contactId, label, email or telephone is included. No-op/replay emit none.
Disabled mode omits storage; stored-only/published store PENDING atomically
without requiring a broker or publishing as part of contact acceptance.
