# ADR 002 — Company and Site invariants

Status: accepted by the foundation design; workflows remain assigned to their HU.

## Sources

- [HU01 / #3](https://github.com/astudilloalex/company-registry-service/issues/3): legal Party identity, tenant uniqueness, registration version and replay.
- [HU03 / #5](https://github.com/astudilloalex/company-registry-service/issues/5): Company state machine and activation prerequisites.
- [HU04 / #6](https://github.com/astudilloalex/company-registry-service/issues/6): local Site registration and retrieval.
- [HU05 / #7](https://github.com/astudilloalex/company-registry-service/issues/7): Site independence, headquarters protection and shared Site revision for child mutations.
- Existing V1 migration: uniqueness, qualified foreign keys and immutable identity/terminal-state triggers.

## Identity and ownership

A Company references one Party, with at most one Company per `(tenant, party)` even after archival. Party Registry owns legal names, identifiers and documents. Company owns operational information, not a legal identity copy. Tenant and Party are immutable; UUID references to external systems remain opaque.

Company and Site are distinct aggregates. Site's parent Company/tenant and original creation identity are immutable. Address and Contact are owned by Site and cannot be reassigned. Reads/writes must qualify tenant and Company, including child queries joined through Site. Never infer tenant access from a globally unique identifier alone.

## Lifecycle

| Aggregate | Allowed effective transitions | Terminal |
| --- | --- | --- |
| Company | DRAFT→ACTIVE/ARCHIVED; ACTIVE→INACTIVE; INACTIVE→ACTIVE/ARCHIVED | ARCHIVED |
| Site | DRAFT→ACTIVE/CLOSED; ACTIVE→INACTIVE/CLOSED; INACTIVE→ACTIVE/CLOSED | CLOSED |

Terminal records reject ordinary changes, not just status changes. A same-state request is not an effective transition; future command contracts distinguish no-op from accepted idempotent replay explicitly.

Company activation requires an existing ACTIVE LEGAL_ENTITY in its tenant and exactly one current legal HEADQUARTERS with Site ACTIVE. A Site may become ACTIVE under Company DRAFT or INACTIVE, enabling preparation of the first headquarters and later Company reactivation. Company ARCHIVED prevents Site activation. Effective operativity is derived from `Company ACTIVE && Site ACTIVE`; never store a separate mutable flag or cascade Company deactivation into Site status updates.

## Legal headquarters and concurrency

HEADQUARTERS means legal headquarters. At most one non-CLOSED headquarters exists per Company; DRAFT and INACTIVE also occupy the unique slot. Closed historical headquarters do not. The partial unique index enforces only the maximum, not the full Company activation prerequisite.

Future Company activation and conflicting headquarters mutations lock **Company → Site**, then revalidate local state under the same transaction. No reverse lock order. Provider HTTP validation must not hold long-lived database locks; a later Party change is not covered by distributed atomicity.

An ACTIVE Company cannot lose, deactivate, close or reclassify its only active headquarters. V1 replacement is: deactivate Company → close old headquarters → register replacement → activate replacement → reactivate Company. No atomic replacement workflow is added in T00.

## Versions, replay and events

### HU04 registration decision

New Site registration is admitted locally under a tenant-qualified Company in
DRAFT, ACTIVE or INACTIVE. ARCHIVED rejects a new registration. Every Site type
(HEADQUARTERS, BRANCH, WAREHOUSE and OTHER) must acquire the Company lock before
creating Site, then recheck durable acceptance before checking current admission.
An equivalent accepted retry returns the original result before consulting current
Company or Site state. Registration does not call Party or geographic providers.

Site starts at DRAFT/version 0 with one UTC microsecond instant and the exact actor
in both creation/update audit pairs (AC01). Registering Site leaves Company's
status, version and all four audit values untouched (AC14); acquiring its lock is
not a Company mutation. Effective operativity is computed on reads using the
qualified Company/Site states, while registration replay retains its original false.

The canonical code remains reserved within Company for every Site status,
including CLOSED. Closing headquarters releases only the non-CLOSED headquarters
slot, so replacement requires a different, free code and preserves the historical
Site (AC07). Other Companies can use the same code. This decision supplies no
activation, closure, archival or atomic headquarters-replacement workflow; those
remain HU03/HU05 responsibilities with Company → Site lock ordering.

Company and Site start at version zero. Every effective mutation increments the owning aggregate exactly once; Address/Contact mutations use Site's version. No-op and replay preserve versions and do not duplicate events. Company mutations do not increment Site, or vice versa. Event `aggregate_version` is the resulting business revision; outbox `version` is independent delivery concurrency state.

Future writes commit aggregate + accepted idempotency snapshot + conditional outbox together. Replay returns the original versioned accepted result, not a fresh query. ARCHIVED uses `company.updated.v1`; no unapproved archival event. Site activation/deactivation/children changes use `company.site.updated.v1`, and closure uses `company.site.closed.v1`.

## Acceptance boundary

T00 supplies pure policies and database integrity checks. It does not implement the registration, activation, headquarters replacement or child mutation use cases. SQL concurrency tests must use independent transactions; pure policy tests do not establish transaction correctness.
