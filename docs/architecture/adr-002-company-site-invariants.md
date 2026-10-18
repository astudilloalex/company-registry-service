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

Terminal records reject ordinary changes, not just status changes. A new Company
activate on ACTIVE or deactivate on INACTIVE returns 409
`company-already-in-requested-state` without consuming its key or changing audit,
version or events. ARCHIVED has precedence over redundancy and returns 409
`company-archived` for all three commands. An equivalent durable replay returns
its original 200 before current ownership/version/state checks, even after another
cycle or archival. Company PATCH retains its separate no-op contract.

Company activation requires an existing ACTIVE LEGAL_ENTITY in its tenant and exactly one current legal HEADQUARTERS with Site ACTIVE. A Site may become ACTIVE under Company DRAFT or INACTIVE, enabling preparation of the first headquarters and later Company reactivation. Company ARCHIVED prevents Site activation. Effective operativity is derived from `Company ACTIVE && Site ACTIVE`; never store a separate mutable flag or cascade Company deactivation into Site status updates.

## Legal headquarters and concurrency

HEADQUARTERS means legal headquarters. At most one non-CLOSED headquarters exists per Company; DRAFT and INACTIVE also occupy the unique slot. Closed historical headquarters do not. The partial unique index enforces only the maximum, not the full Company activation prerequisite.

Company activation and conflicting headquarters mutations lock **Company → Site**,
then revalidate local state under the same transaction. No reverse lock order.
Activation preparation releases its transaction/session before Party HTTP; final
acceptance resolves the current non-CLOSED headquarters again, since Site writes
do not increment Company.version. A later Party change is not covered by
distributed atomicity. Deactivate and archive are local operations; neither
changes any Site, child, Site revision or Site audit.

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

### HU05 local mutation decisions

`SiteInformationPatch` preserves explicit presence for code/name/siteType. Its pure
policy retains omitted values, rejects explicit null, and never applies HU04's
BRANCH default. `SiteLifecyclePolicy` evaluates parent ARCHIVED before Site CLOSED,
then command redundancy/eligibility and active legal headquarters protection.
Ownership and expected revision must be established before invoking that policy.
The information and lifecycle deltas in
`openspec/changes/archive/2026-10-18-add-company-site-lifecycle-management/specs/`
define these rules; their accepted requirements are synchronized in `openspec/specs/`.

`SiteMutation.Command` identifies UPDATE, ACTIVATE, DEACTIVATE or CLOSE with separate
`company.site.{update,activate,deactivate,close}.v1` namespaces. Commands preserve raw
partial text until the applicable fingerprint policy is selected. Lifecycle commands
carry empty intent. The neutral result retains the observed Company status internally
so historical operativity can be verified without consulting present aggregate state.
`SiteMutationFailure` separates ownership, revision, state, invariant, uniqueness,
idempotency and availability outcomes; presentation supplies status/code mappings.

Every new acceptance, including PATCH no-op, must lock qualified Company then Site,
recheck durable acceptance after acquiring Company, and validate the Site revision
before states. Effective changes increment only Site once; no-op preserves Site
audit/revision and may have its own durable completion audit. Code A → B releases A
only at commit. CLOSED retains its current code. These decisions do not implement
Company activation or address/contact endpoints; compatible future writers must use
Company → Site and the common Site revision. Pure policy tests establish domain
decisions; SQL/HTTP concurrency and atomicity require separate integration evidence.

T00 supplies pure policies and database integrity checks. It does not implement the registration, activation, headquarters replacement or child mutation use cases. SQL concurrency tests must use independent transactions; pure policy tests do not establish transaction correctness.
