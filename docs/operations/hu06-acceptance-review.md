# HU06 acceptance review

Change: `add-company-site-address-management`, schema `spec-driven`.
Acceptance source: [HU06, issue #8](https://github.com/astudilloalex/company-registry-service/issues/8),
including the agreed OpenSpec deltas and design. Review date: 2026-10-18.

## Execution record

Verification is in progress. Pending rows below are not execution evidence.

| Stage | Result | Evidence |
| --- | --- | --- |
| Focused JVM/PostgreSQL | 107 tests, zero failures/skips before final codec compatibility regression | `focused-profiles` run |
| Full clean JVM build / production isolation | Pending | `build/hu06-evidence/jvm/` |
| Packaged JVM process | Pending | `build/hu06-evidence/packaged-jvm/` |
| Mandrel native build / native process | Pending | `build/hu06-evidence/native-process/` |
| Strict OpenSpec validation | Pending | Run after final task reconciliation |

Reports are preserved per stage with `docs/operations/hu06-test-results.gradle`.
The JVM test heap is 1 GiB: the expanded full suite exhausted Gradle's default
test heap after 313 cases without an assertion failure. Early harness runs also
encountered a closed Podman API connection and application-resource reuse across
scoped test classes. A dedicated non-idling Podman service and the established
shared `QuarkusTestProfile` pattern make the compositions explicit and isolated.

Reproducible environment and commands:

```shell
# Keep this process alive in a separate terminal until verification finishes.
podman system service --time=0 unix:///tmp/hu06-podman-01a150f1.sock
export DOCKER_HOST=unix:///tmp/hu06-podman-01a150f1.sock
export TESTCONTAINERS_RYUK_DISABLED=true
export QUARKUS_HTTP_TEST_PORT=0

./gradlew --offline -I docs/operations/hu06-test-results.gradle -Phu06EvidenceRun=jvm clean build verifyProductionIsolation
./gradlew --offline -I docs/operations/hu06-test-results.gradle -Phu06EvidenceRun=packaged-jvm quarkusIntTest
./gradlew --offline quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
./gradlew --offline -I docs/operations/hu06-test-results.gradle -Phu06EvidenceRun=native-process quarkusIntTest -x quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
openspec validate add-company-site-address-management --strict --json
```

## AC01–AC16 evidence map

| Criterion | Assertions / executable evidence |
| --- | --- |
| AC01 creation under Site | `AddressHttpTest.creationNoOpCorrectionDeletionAndHistoricalReplayUseExactPublicShapes`; `MutateAddressTest.creationQueriesGeographyOnlyBetweenFinalizedSessionsAndAcceptsOnce`; `PackagedAddressIT` POST |
| AC02 country validation | `CountryReferenceAdapterTest`; `AddressGeographyTest.missingOrInactiveCountryShortCircuitsDivision`; `AddressHttpTest.geographyRejectsMissingInactiveIncompatibleAndUnavailableReferencesWithoutAcceptance`; packaged POST |
| AC03 division membership | Both entries in `DivisionReferenceAdapterTest`, including matching country/code and incompatible successful responses; `AddressGeographyTest.distinguishesDivisionAbsenceAndEveryInactiveState`; HTTP and packaged creation with division |
| AC04 dependency failure | `ReferenceFailureTest`; `AddressBudgetHttpTest.totalDeadlineBoundsRequiredHttpAndTheUnconsumedKeyCanBeUsedLater`; HTTP absence/inactivity/502/503 cases; no accepted address/key/event on rejection |
| AC05 optional coordinates | `AddressCoordinatesTest.omissionPreservesPairAndExplicitNullClearsIt`; minimum HTTP creation with eleven fields and no coordinates |
| AC06 indivisible pair | `AddressCoordinatesTest.rejectsMissingMemberEvenIfPreviousPairExists` / `rejectsMixedNullAndNumber`; `AddressBoundaryTest.coordinatePrecedenceRejectsIncompleteTypesMixedNullRangeAndPrecision`; HTTP rejection |
| AC07 ranges and exact precision | `AddressCoordinatesTest` inclusive limits, just-outside values, exact six decimals, extra zeros and numeric zero; HTTP and packaged POST/PATCH exact numbers |
| AC08 primary uniqueness | Existing `AddressIntegrityTest`; `AddressPrimaryPolicyTest`; `AddressConcurrencyHttpTest.primaryAndReclassificationRacesAcceptOneRevisionAndOneEvent` |
| AC09 atomic primary replacement | `AddressRepositoryTest.demotionPrecedesReclassificationAndSiteAdvancesOnlyOnceWithoutChangingCompany`; HTTP common-audit/reclassification checks; injected completion/outbox rollback |
| AC10 normalization | `AddressTextTest` required/optional blanks, accents, internal spaces, Unicode expansion and code-point limits; policy no-op equivalence; actual HTTP and packaged canonical text |
| AC11 shared concurrency | HTTP races against HU05, Company archival, another child writer and changed/deleted local data during HTTP; stale existing addresses receive 412 after absence checks |
| AC12 one Site increment | Repository/common audit assertions, use-case creation/no-op, HTTP version/event counts and concurrent pages; Company revision remains unchanged |
| AC13 logical removal | HTTP 200 minimal five-field receipt, historical row retained, primary cleared, GET/list exclusion, new DELETE 404 and original replay; packaged restart |
| AC14 idempotent creation | Parallel keyed POST/DELETE, cross-Company key reuse and rollback; stable HMAC vectors; `AddressAcceptanceTest` policy/format/K1→K2; packaged historical replay after restart and terminal parents |
| AC15 isolation | Qualified repository/detail/page and HTTP parent/address ownership; cursor binds collection/tenant/Company/Site/filters/order; incompatible reuse returns sanitized 400/404/409 |
| AC16 atomic Site event | Injected failure rolls back address/demotion/Site/completion/event; no-op/replay add no event; packaged disabled/stored-only/published modes validate minimum Site event schema and PENDING/zero-attempt state |

## Additional agreed scenarios

- `AddressBoundaryTest` checks complete JSON structural precedence, unknown and
  duplicate properties, exact types, omission/null/value, required fields and
  coordinate precedence. `AddressHttpTest` verifies current Process-Id and
  two-field public error envelopes, including 400/404/409/412/415/500/502/503.
- Geographic planning validates only new canonical assignments. Country changes
  retain an omitted division; null explicitly removes it. Conserved references,
  division-only removal, local correction, GET, DELETE, no-op and replay need no
  provider. Confirmed absence, known inactivity and invalid/unavailable responses
  remain distinct. Provider provenance and admitted headers are recorded in
  `docs/contracts/external-references.md`.
- `AddressConcurrencyHttpTest.remoteWaitHoldsNoDatabaseLocksAndCurrentDeletionRevisionOrReplayWinOverRemoteFailure`
  uses provider barriers and an independent JDBC lock probe. HTTP runs after
  preparation closes. New protection applies replay, absence, revision and
  Company/Site state before the remote observation.
- `MutateAddressTest` covers editable state combinations, no-op, original replay,
  unknown finalization without recovery, and confirmed acceptance with a lost
  response recovered in a fresh session with decreasing time and no second write.
  The dedicated HTTP budget profile exercises actual database lock and external
  waits. `PackagedAddressIT` checks invalid budget at startup.
- V7 preflight/integrity/upgrade tests preserve all six tables and children,
  previous completions/events, immutable identity/audit and existing constraints.
  Full Flyway validation preserves V1–V6 checksums. Catalog/DBML tests check the
  ready/valid partial page index and immediate primary protection; runtime DML
  and migrator-only DDL are checked separately.
- Snapshots remain independent of current rows. Codec tests cover real JSONB,
  numeric scale canonicalization (including `0.000001` and `90`), internal nulls,
  no-op/changed revision and audit, strict shape/types, unknown format, corrupt
  ownership/identity and sanitized failure. UUIDv7 is required for newly generated
  identities; keyed UPDATE/DELETE preserve other existing valid UUID identities.
- Page filters apply with AND before limit+1. Tests cover empty pages, boundaries,
  tied creation dates, descending UUID order, continuation, exact/repeated/unknown
  query inputs, overflow and coherent Site revision during concurrent mutation.
  Cursor tests cover rotation, another instance/collection, altered scope/filter,
  unknown format/key and permitted page-size changes.

## Retention, rollback and scope limits

Keep V7, accepted/deleted rows, original completions/events, policy 1, format 1
and historical keys without TTL. Deploy compatible code or suspend HU06 routes
on rollback; do not erase keys, reset revisions or restore addresses to permit
another acceptance. No historical repair or current-row fallback is performed.

The other-child concurrency fixture tests the shared Company→Site protection
and revision protocol; it does not implement or claim HU07 contact management.
Geographic acceptance is an observed contract result, without distributed
atomicity against later provider changes. Pagination observes current pages and
does not provide a cross-page snapshot. Outbox remains stored PENDING; HU08
publication and broker delivery are outside HU06.

JVM tests do not establish native execution. `PackagedAddressIT` is prepared for
the actual productive JVM/native process with independent PostgreSQL and HTTP
fixtures outside the artifact. Native results, artifact identity and concrete
execution coverage must be recorded before completing this review.
