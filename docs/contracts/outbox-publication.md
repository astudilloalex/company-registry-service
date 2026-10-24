# Transactional outbox publication (HU08)

Business mutations and enabled events commit together. Only `published` delivers;
`disabled` captures no new events and `stored-only` captures without delivery.
Switch mode by configuration and restart of the same artifact. No mode backfills
events from disabled acceptances, purges history or reactivates FAILED/PUBLISHED.

## Configuration

The [README configuration table](../../README.md#transactional-outbox) lists the
`COMPANY_OUTBOX_*` variables and defaults. Durations must be positive and
representable: polling ≤1h, timeout ≤5m, lease ≤1h and greater than timeout,
initial/maximum backoff ≤24h with maximum ≥initial. Batch 1–500; attempts 1–100.
Default polling/batch/timeout/lease/backoffs/attempts: 5s/25/5s/30s/1s/5m/10.
The default exchange is `company.events` and routing is the literal template
`%s`, substituted by eventType exactly once. `integration.%s` is valid.
The old reserved fixed `company.events` routing override must migrate to `%s`.
Other formats, controls, empty destinations or routes exceeding 255 UTF-8 bytes
are rejected at startup for all seven types. No `String.format` evaluation occurs.

Broker properties under `company-registry.outbox.broker` map to
`RABBITMQ_HOST`, `RABBITMQ_PORT` (5672), `RABBITMQ_USERNAME`,
`RABBITMQ_PASSWORD`, `RABBITMQ_VIRTUAL_HOST` (`platform`). Host and credentials
are required only in published; port range is 1–65535. Keep credentials external.
Both other modes work without these variables or a running broker. Broker health
is excluded from API readiness/liveness; database checks remain enabled.

## Original fact and delivery

The closed catalog remains seven schema-1 events. Runtime validates closed
Company/Site forms, UUIDs, revisions, states and identity/date equalities before
sending; invalid contracts fail terminally with no payload in diagnostics.
Each message has exactly the eleven approved envelope fields including explicit
null causationId. No query to Company, Site, children or external providers
reconstructs a fact. Later mutations do not alter the event's original snapshot.

Message ID equals eventId; delivery mode is persistent (2), content type JSON,
type is eventType and timestamp/correlation come from the original fact.
PUBLISHED means a positive broker confirm, without proving consumer processing.
It can also mean a confirmed message with no matching binding: creating a queue
afterwards cannot recover such a message. A durable bound queue can retain a
persistent message while its consumer is stopped, subject to queue policies.

Delivery is at least once and may be out of order even for the same aggregate.
Deduplicate by eventId and use aggregateVersion to detect gaps/reordering.
A revision in backoff or FAILED never blocks later eligible revisions.

## Reservations and recovery

PostgreSQL time determines eligibility. Short transactions select PENDING rows
in `coalesce(next_attempt_at,created_at),created_at,id` order with SKIP LOCKED.
At reservation, increment publish_attempts once, set last_attempt_at and lease
in next_attempt_at, clear errors and update technical audit/version. Commit before
waiting for RabbitMQ. Each concurrent delivery has an independent Vert.x context
and session; no database lock spans AMQP. Local scheduled polls do not overlap.

Every result uses a new transaction matching eventId, tenantId, attempt,
technical version, last_attempt_at and lease. A prior result or a new reservation
invalidates this identity, so duplicate/late results return false without writes.
The technical version and attempt count never replace aggregateVersion.

Recoverable timeout, nack or connection failures schedule
`min(initialBackoff * 2^(attempt-1), maximumBackoff)` from database failure time.
The computation saturates without overflow. A timeout is uncertain: a message
may still arrive later. Connector send retries are zero; reconnection uses a
separate long-lived budget. Emitter/inflight/internal queue capacities follow
the batch size, bounding outstanding transport work.

A reserved attempt consumes budget even if its process dies. An expired final
reservation becomes FAILED with `delivery-outcome-unknown`, never reserving
maximum+1. Known errors retain their allowlisted code; reduced limits can finish
eligible backoff rows without sending. FAILED preserves the fact and does not
prove non-delivery: messages may arrive after timeout or terminal failure.
There is no administrative reactivation in HU08.

After a broker confirm followed by a failed/unknown local transition, leave the
reservation recoverable. Do not retry the write or start a competing recovery
write over a transaction whose finalization is unknown. Expiry may produce a
duplicate with identical eventId/content. Cancellation leaves the same protection.

## Deployment, metrics and rollback

Provision RabbitMQ and the existing vhost outside runtime. Use a dedicated user
with write permission limited to the configured exchange; exchange declaration
needs configure permission limited to that name. No consumer read/queue creation
permission is needed. The exchange is topic/durable. Prepare and verify consumer
queues/bindings against effective routes before enabling published. Tests use a
disposable RabbitMQ container and temporary queues, never shared topology.

Prometheus `/q/metrics` exposes `company_outbox_delivery_total` with low-cardinality
event_type/outcome/code labels: confirmed, published, retry, failed, stale and
transition_uncertain. Confirmations and committed transitions are separate:
confirmed can increase without published when persisting the result fails.
`company_outbox_exhausted_total` counts committed expiry finalizations;
`company_outbox_poll_failures_total` reports persistence/poll failures.
`company_outbox_pending` and `company_outbox_oldest_age_seconds` include reservations
and backoff; age is from occurredAt. `company_outbox_backlog_available` is 1 after
a successful query. All three backlog gauges are registered at startup in every
mode, including disabled and stored-only. Until a successful query, pending and
age are NaN and availability is 0; these values represent unavailable data, not
zero backlog. Registering or reading these metrics does not activate delivery,
reserve rows or open an AMQP connection in modes without delivery.

Alert on increasing oldest age/backlog, retries, terminal failures or uncertain
transitions and unavailable measurements. Logs contain event/tenant/correlation,
attempt and allowlisted outcome codes; no payload, raw dependency exception,
credential, legal or contact data. Identity is never a metric label.

Deploy initially stored-only, check topology and API health, then restart in
published and monitor. To stop delivery, restart in stored-only without deleting
history; reservations keep their expiry. Roll back code in stored-only/disabled
while retaining rows, accepted results, keys and migration history. Do not edit
V1–V8 or turn terminal events back into PENDING.
