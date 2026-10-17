# T00 acceptance review

Source: [issue #2 — Technical Foundation, Architecture and Database](https://github.com/astudilloalex/company-registry-service/issues/2).
This reviews the 16 acceptance criteria and the user-confirmed mandatory native
delivery. OpenSpec implementation tracking is distinct from a Git commit,
remote reviewer approval or issue closure. No business CRUD, publisher, public
proxy, Keycloak integration or production deployment is claimed.

## Acceptance traceability

| # | Issue acceptance criterion | Evidence | Review result |
| --- | --- | --- | --- |
| 1 | Compiles with approved Java/Quarkus | Java 25 / Quarkus plugin+BOM 3.40.1; final `./gradlew clean build`; both Mandrel JDK 25 native builds | Verified |
| 2 | Starts without a public reverse proxy | JVM integration, both native process suites, real non-root Podman image readiness without proxy | Verified |
| 3 | Six application tables created by Flyway | `DatabaseStartupTest`, `SchemaCatalogIntegrityTest`, native `PackagedRestartIT`; six tables plus Flyway history | Verified |
| 4 | Required uniqueness/FK/partial-index/CHECK constraints tested on PostgreSQL | [Constraint inventory](../database/constraint-verification.md), six SQL integrity suites, installed-catalog assertions | Verified |
| 5 | A Party cannot map to two Companies within one tenant | `CompanyIntegrityTest.primaryKeyAndTenantQualifiedUniqueness` and `.archivedPartyAndCodeStayReservedAndStateIsTerminal`; cross-tenant reuse allowed | Verified |
| 6 | Concurrent non-closed legal HQ creation preserves uniqueness | `SiteIntegrityTest.concurrentHeadquartersHaveExactlyOneCommit`: independent transactions, observed unique lock wait, exactly one commit | Verified |
| 7 | Invalid coordinate combinations rejected | `AddressIntegrityTest.coordinatePairsAndInclusiveBounds`: nullable pair, one missing coordinate, inclusive extrema and out-of-range cases | Verified |
| 8 | Independently configurable runtime/migration roles | README variables/bootstrap; `DatabaseStartupTest`; native repeated-start and fresh-image installation with separate roles | Verified |
| 9 | Hibernate Reactive does not create/modify schema | `schema-management.strategy=none`; runtime role cannot DDL in JVM/native tests; tables owned by migration role | Verified |
| 10 | Business logic independent of Infrastructure frameworks | Domain/Application ArchUnit rules plus negative fixtures; [reactive ADR](../architecture/adr-001-reactive-native-foundation.md); I/O ports in Application as explicitly approved | Verified |
| 11 | Business headers/responses documented and testable | [OpenAPI](../contracts/company-registry.openapi.yaml), HTTP/context/strict JSON/signature tests, native HTTP suite; exact Process-Id and shared status/code/data semantics | Verified |
| 12 | Three approved outbox modes | `OutboxPolicyTest`, `OutboxConfigurationTest`; disabled default, stored-only/published storage selection, invalid settings rejected | Verified |
| 13 | Disabled startup without reachable RabbitMQ | `OutboxBootTest`, native process and OCI readiness; no connector/channel/emitter/scheduler or broker credentials required | Verified |
| 14 | Architecture documents committed and aligned | [Central handoff](../architecture/company-registry-architecture-handoff.md), local central C3/context/ownership updates, LikeC4 validation/build passed; documentation/source links reviewed | **Aligned and prepared locally; commit/versioning pending explicit request** |
| 15 | Clean database installation succeeds | Fresh PostgreSQL 18 containers in migration/native suites and independent OCI installation; immutable V1, no repair | Verified |
| 16 | Repeated validation preserves schema history | `DatabaseStartupTest.repeatedStartupMigrationAndValidationLeaveHistoryUnchanged`; native `PackagedRestartIT` starts a second binary against persisted DB and compares history | Verified |

Criterion 14 is not silently accepted: both the service's new/modified documents
and the central model follow-up include uncommitted changes. The prior central
baseline is [5746268](https://github.com/astudilloalex/alex-astudillo-architecture/commit/5746268).
The final C3/client/native status follow-up has local file links in the handoff
and passed LikeC4 1.60.0 `validate` (24 files), static-site `build`, and
`git diff --check`. It has not been assigned a nonexistent commit or PR URL.
The OpenSpec task to review/prepare these files can be complete while this issue
publication criterion remains pending; do not close the issue solely from task
checkboxes.

## Mandatory native delivery

See [native-verification.md](native-verification.md) for exact commands, builder
digest/version, platform, resource limits, output hashes and preserved reports.

| Artifact | Completed execution evidence |
| --- | --- |
| Production native binary | Mandrel 25.0.4.1, ELF x86-64-v3; actual runner launch in native XML report; health/OpenAPI, no auxiliary routes, clean/repeated Flyway, runtime without DDL, source-free second startup with providers unavailable |
| Explicit verification native binary | Separate `build/verification/` compilation; six reactive mappers and rollback, JSONB including explicit null keys, enums/Instant/audit, shared envelope/validation/errors, concurrent context, Party/country/confirmed-division stubs and failure scenarios, actual refused connections from a second process, distinct runtime UUIDv7 |
| Production OCI image | UBI 9 Minimal, user 1001, direct PID 1 native executable with matching SHA-256, no Java on PATH, no source mounts, fresh PostgreSQL with separate roles, disabled/no broker, operational endpoints and absent fixture routes |

The initial 15-minute tool timeout and the subsequent 3 GiB compiler Java-heap
OOM are retained as historical failed attempts, not acceptance. Both 5 GiB/two-
thread builds succeeded in about ten minutes. These are host-resource-specific
observations, not claims that the machine lacked disk space or total RAM.
No native requirement is marked complete using only JVM evidence.

## Final verification and review preparation

- `./gradlew clean build`: **120 passing JVM tests, 37 suites, zero skipped or failed tests**.
- `./gradlew quarkusIntTest`: passed after the final clean build.
- Both explicit native builds and native process suites: passed; production
  selection intentionally excludes the verification-only suite, while the
  verification variant ran all seven applicable checks without skips.
- Podman native production image build/start: passed and disposable test
  containers/network removed afterward.
- V1 working-tree diff is empty; native fresh installation recorded checksum
  `-272976494`. No corrective migration or Flyway repair was needed.
- Resource signatures and native HTTP responses use API DTOs with shared
  envelopes. Production excludes verification sources at compilation; the
  static contract exposes no fake commercial paths.
- Geographic division contract is confirmed and implemented, not left blocked:
  [provider provenance and adapter evidence](../contracts/external-references.md).
- Native reports/metadata/runners were preserved before the final clean JVM
  build in `/tmp/opencode/company-registry-native-evidence.tar`, extracted under
  `/tmp/opencode/company-registry-native-verified/`; runner hashes were unchanged
  after restoration. Current root Quarkus metadata correctly describes the last
  JVM build; follow the documented build→test sequence for future native tests.
- No event-loop-blocking failure surfaced in the combined reactive tests.
  Deliberately triggered sanitized-500/constraint logs are expected fixtures,
  not leaked API responses or failed tests.
- Staged/untracked user work is retained. Files are prepared for review in their
  respective repositories; no commit, amend, push, PR or issue update was made.

The remaining acceptance action is requested versioning/review, not an omitted
integration, unconfirmed provider route, missing diagram or skipped native build.
