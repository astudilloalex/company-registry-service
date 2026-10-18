# Company Registry — central architecture handoff

## Status and scope

### HU03 Company lifecycle decisions

The repo-local `add-company-lifecycle-management` change defines three explicit
commands: ACTIVATE (DRAFT/INACTIVE → ACTIVE), DEACTIVATE (ACTIVE → INACTIVE) and
ARCHIVE (DRAFT/INACTIVE → ARCHIVED). `CompanyLifecyclePolicy` reuses CompanyStatus;
terminality, redundancy and forbidden transitions remain distinct neutral reasons.
`CompanyActivationPolicy` requires a current ACTIVE headquarters and an ACTIVE
LEGAL_ENTITY; it does not change HU01 registration eligibility or require children.

A new redundant command returns 409, while an equivalent accepted replay returns
its original 200 before current preconditions, including after archival. The three
`company.{activate,deactivate,archive}.v1` namespaces are independent. API owns
status/code mappings; Application commands and failures contain no transport or
persistence framework types. The result is the local operational CompanyRecord.

Company → Site is the shared lock order. Activation releases local preparation
before Party HTTP and resolves the current headquarters again under final
protection. Company and Site revisions/audit remain independent: transitions
write only Company, Site operativity remains derived, and Site replay keeps its
historical value. Deactivate/archive/replay require no provider calls. These are
architectural contracts; the change's acceptance record must separately establish
executed SQL, HTTP, concurrency, packaged, native and OCI evidence.

Central model completed and checked through LikeC4 MCP, CLI and Git on 2026-10-16.
The existing [commit 5746268](https://github.com/astudilloalex/alex-astudillo-architecture/commit/5746268)
provides the service, logical database, context and deployment baseline. The
user-authorized coordinated follow-up now adds the four components and C3 view,
updates ownership, and aligns external relationships with the verified foundation.
Task 10.2's incorporation/validation is complete in the central working tree;
the full service T00 acceptance remains open for the other implementation tasks.

The OpenSpec action context remains repo-local to `company-registry-service`.
The central edits were performed as the separately coordinated work explicitly
requested by the user, in
`/home/alex/Documents/Development/architecture/alex-astudillo-architecture`.
They do not expand the local change's CLI-controlled edit roots or create another
OpenSpec root. The follow-up is implemented and validated locally, with source
links and review evidence below; it has not been committed, pushed or submitted
as a PR. Publication is not claimed by checking the implementation task.

## Existing authoritative elements and conventions

| Existing FQN | Kind / purpose |
| --- | --- |
| `alexAstudilloPlatform` | `softwareSystem`, platform parent |
| `alexAstudilloPlatform.partyRegistryService` | `container`, legal Party provider |
| `alexAstudilloPlatform.geographicReferenceService` | `container`, country/division provider |
| `alexAstudilloPlatform.postgreSQL` | `databaseContainer`, existing shared PostgreSQL 18.x instance |
| `alexAstudilloPlatform.postgreSQL.partyDb` | Separate Party logical database; Company must not access it |
| `alexAstudilloPlatform.postgreSQL.geographicDb` | Separate geographic logical database; Company must not access it |
| `alexAstudilloPlatform.rabbitMQ` | `queueContainer`, optional future outbox delivery |

Authoritative sources:

- [Service catalog](/home/alex/Documents/Development/architecture/alex-astudillo-architecture/catalog/system/microservices.c4:9).
- [Shared PostgreSQL and logical databases](/home/alex/Documents/Development/architecture/alex-astudillo-architecture/catalog/system/database.c4:3).
- [Kinds/styles](/home/alex/Documents/Development/architecture/alex-astudillo-architecture/catalog/specification/elements.c4:32).
- [Party service-context view convention](/home/alex/Documents/Development/architecture/alex-astudillo-architecture/architectures/party-registry/party-registry-overview.c4:2).

Reuse central styles: `container` is brightCerulean; `databaseContainer` is storage/brightCerulean; `component` is mayaBlue; `queueContainer` is queue/brightCerulean. Services use `tech:quarkus`; PostgreSQL uses `tech:postgresql`. There are no declared relationship kinds or tags in this model, so the change must not assume unpublished kinds/tags.

## Catalog elements present centrally

| FQN | Kind | Title / responsibility |
| --- | --- | --- |
| `alexAstudilloPlatform.companyRegistryService` | `container` | Company Registry Service — tenant-scoped operational Companies/Sites, owned addresses/contacts, idempotency and conditional outbox; legal identity remains external |
| `alexAstudilloPlatform.postgreSQL.companyDb` | `databaseContainer` nested under existing PostgreSQL | `company_registry_service` — six owned tables plus Flyway technical history |
| `alexAstudilloPlatform.companyRegistryService.api` | `component` | Verified HTTP boundary and response/error mapping |
| `alexAstudilloPlatform.companyRegistryService.application` | `component` | Neutral context, results/failures and implemented asynchronous I/O ports |
| `alexAstudilloPlatform.companyRegistryService.domain` | `component` | Pure Company/Site concepts and text/contact policies |
| `alexAstudilloPlatform.companyRegistryService.infrastructure` | `component` | Verified SQL/Flyway, reactive adapters and outer composition |

Service technology: `Quarkus | Java 25 | Mutiny | Hibernate Reactive`, native delivery with Mandrel JDK 25 and Podman. Database technology: `PostgreSQL Database`, under the existing PostgreSQL 18.x parent. Do not create another PostgreSQL instance, deployment, or local catalog to represent this logical database.

## Central relationships

| Source | Target | Label / technology | Delivery scope |
| --- | --- | --- | --- |
| Company service | `postgreSQL.companyDb` | Persist separate Company/Site aggregates, children, idempotency and optional outbox — Hibernate Reactive / Mutiny / PostgreSQL | T00 foundation; runtime access reactive |
| Company service | Party service | Resolve tenant-scoped legal Party identity/type/status — REST Client / Mutiny / JSON | T00 client foundation, HU01/HU03 workflows |
| Company service | Geographic service | Resolve country alpha-2 and confirmed administrative divisions — REST Client / Mutiny / JSON | Both adapters verified after provider contract confirmation |
| Company service | RabbitMQ | Optionally publish configured Company outbox events — Reactive Messaging / AMQP | **Future HU08**, no publisher/channels/scheduler in T00; default disabled |

All FQNs above have the `alexAstudilloPlatform` prefix. External edges originate
at `.infrastructure` and roll up to the Company service in C2 views; they are not
duplicated at both levels. There are no Company edges to provider databases.
Migration JDBC/Flyway is startup-only access to the same Company logical database
with a separate role, not an operational request integration.

The local SQL, reactive persistence, HTTP, external-client and outbox foundation
blocks are now verified. Division lookup was confirmed from Postman plus the
provider checkout and passed JVM/native tests; it is not still blocked. Native
production/verification process suites and the UBI 9 non-root Podman image passed.
As the final coordinated alignment follow-up, central descriptions/edges were
updated to reflect these results, retaining future HU01-HU07 workflows and HU08
publication. Evidence is in [native verification](../operations/native-verification.md)
and [external contracts](../contracts/external-references.md).

## Incorporated views

1. `companyRegistryOverview`: platform boundary, service, shared PostgreSQL parent
   with Company DB, Party/Geographic providers and future/optional RabbitMQ.
2. `companyRegistryComponents`: four nested components with API→Application→Domain
   and Infrastructure→Application/Domain. Panache composition and startup Flyway
   remain outside the inner layers. Descriptions reflect verified policies,
   adapters, HTTP/clients and native execution, with commercial workflows still future.
3. `containerOverview` and `businessOwnership`: operational Company/Site ownership
   explicitly separate from Party legal identity. The already-existing deployment
   views were preserved; this follow-up establishes no new deployment/native evidence.

## Coordinated change evidence

- Central change: [5746268](https://github.com/astudilloalex/alex-astudillo-architecture/commit/5746268),
  `feat: add company registry service architecture and infrastructure components`.
  Follow-up revision: local working-tree changes on this base, not yet committed.
  Central [coordination/validation record](/home/alex/Documents/Development/architecture/alex-astudillo-architecture/architectures/company-registry/README.md:1).
- Incorporated sources:
  [service](/home/alex/Documents/Development/architecture/alex-astudillo-architecture/catalog/system/microservices.c4:15),
  [context view](/home/alex/Documents/Development/architecture/alex-astudillo-architecture/architectures/company-registry/company-registry-overview.c4:2),
  [components and C3 view](/home/alex/Documents/Development/architecture/alex-astudillo-architecture/architectures/company-registry/company-registry-components.c4:1),
  [ownership](/home/alex/Documents/Development/architecture/alex-astudillo-architecture/catalog/views/system-context.c4:11),
  [container overview](/home/alex/Documents/Development/architecture/alex-astudillo-architecture/catalog/views/container-overview.c4:2),
  [external relationships](/home/alex/Documents/Development/architecture/alex-astudillo-architecture/catalog/system/relationships.c4:19),
  [deployment view](/home/alex/Documents/Development/architecture/alex-astudillo-architecture/architectures/company-registry/company-registry-deployment.c4:1).
- `npx --yes likec4@1.60.0 validate`: **valid, 24 source files**.
- `npx --yes likec4@1.60.0 build --output /tmp/opencode/company-registry-central-site --base "./" --use-hash-history`:
  **passed; all views laid out**. Node v25.1.0, npm 11.17.0; outputs are temporary.
- Local review of the central diff and MCP `read-view` results for C3, C2 and
  `businessOwnership` confirmed inward dependencies, reused kinds/styles,
  Company DB nesting, no cross-service DB access, and explicit foundation/future
  labels. This is local review evidence, not a remote reviewer approval.
- Final coordinated label update: LikeC4 1.60.0 `validate` and static-site `build`
  both passed again with 24 source files; `git diff --check` passed. It removes
  stale pending-client/blocked-division/unverified-native claims without claiming
  commercial workflows or a production deployment.
- Both repositories' follow-up documents/model remain local changes. T00 issue
  #2's requirement that architecture documents be committed is recorded as
  pending versioning in the acceptance review; no commit/PR is implied here.
