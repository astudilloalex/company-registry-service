# Company Registry boundaries

Base package: `com.alexastudillo.companyregistry`.

| Layer | Responsibility | Initial implementation |
| --- | --- | --- |
| Domain | Synchronous invariants and business concepts | `AggregateVersion`, Company/Site policies and text normalization |
| Application | Neutral operation context, asynchronous I/O contracts and future meaningful workflows | `OperationContext`; persistence/reference ports belong here |
| API | HTTP parsing, DTO validation, response mapping and public error translation | `ExpectedVersionParser`; this is an internal decimal convention, not an ETag |
| Infrastructure | Composition, reactive persistence, external adapters and configuration | UTC `Clock` producer, migration resources |

API and Infrastructure depend inward on Application/Domain. Application must not import API, Infrastructure, HTTP, SQL, ORM or Quarkus. Domain has no Mutiny, CDI, persistence, JSON or transport dependencies. The API may use Domain value types when parsing into neutral inputs; it never serializes them as the public business payload.

Application I/O ports may return Mutiny `Uni` under the explicit reactive profile. Adapters/composition wrap complete operations with programmatic Panache sessions/transactions. Return composed pipelines; never subscribe or await on request paths, and never combine concurrent work on one Hibernate Reactive session. JDBC is a startup migration island only.

No pass-through use cases are introduced for T00. Registration, activation and listing workflows belong to their HU; a use case is justified by orchestration, authorization, transaction coordination or meaningful business behavior. API success methods must map to an API DTO before calling the CDI `ResponseManager`. Global errors are owned by the published shared module.
