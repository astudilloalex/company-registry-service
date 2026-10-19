# HU06 acceptance review

Change: `add-company-site-address-management`, schema `spec-driven`.
Acceptance source: [HU06, issue #8](https://github.com/astudilloalex/company-registry-service/issues/8),
including the agreed OpenSpec deltas and design. Review date: 2026-10-18.

## Startup-budget regression follow-up (2026-10-18)

A subsequent review reproduced an invalid-budget startup failure on the current
source: the process stayed alive with `0s` after eager composition had been
removed. `AddressOperations.mutation` now has `@Startup`, so the existing
`AddressDeadline` constructor validation runs before startup completes, without
an address request or geographic call. The packaged regression now checks zero
(`0s`), negative (`PT-1S`) and nanosecond overflow (`PT2562048H`), requiring a
nonzero exit and `site-address-budget-invalid` in each case.

These follow-up results supersede the initial readiness claim below. Commands
run from the isolated source copy `/tmp/hu06-budget-fix-01a150f1`; reports are
copied back into the repository without replacing earlier evidence.

| Current-source stage | Result | Evidence |
| --- | --- | --- |
| Full clean JVM/PostgreSQL, build and production isolation | 484 tests passed; zero failures/errors/skips; build/isolation passed | `build/hu06-evidence/budget-fix-jvm/` |
| Productive packaged JVM | 13 cases passed, including all three invalid budgets; four fixture-only cases executed separately | `build/hu06-evidence/budget-fix-jvm/results/quarkusIntTest/` |
| Separate verification JVM | Four cases passed, zero failures/errors/skips | `build/verification/hu06-evidence/budget-fix-verification-jvm/` |
| Productive native build/process | New ELF built; 13 cases passed, including all three invalid budgets; four fixture-only cases reserved for the separate variant | `build/hu06-evidence/budget-fix-native/` |
| Separate verification native | New ELF built; four cases passed, zero failures/errors/skips | `build/verification/hu06-evidence/budget-fix-verification-native/` |
| Strict OpenSpec and final coherence review | Valid, zero issues; current source matches the tested snapshot | `build/hu06-evidence/budget-fix-strict-validation.json` |

Reproducible JVM commands (same Podman environment as the native follow-up):

```shell
export DOCKER_HOST=unix:///tmp/hu06-budget-fix-podman-01a150f1.sock
export TESTCONTAINERS_RYUK_DISABLED=true
export QUARKUS_HTTP_TEST_PORT=0
./gradlew --offline -I docs/operations/hu06-test-results.gradle -Phu06EvidenceRun=budget-fix-jvm clean build verifyProductionIsolation quarkusIntTest
./gradlew --offline -Pverification=true -I docs/operations/hu06-test-results.gradle -Phu06EvidenceRun=budget-fix-verification-jvm quarkusIntTest --tests '*PackagedVerificationIT'
```

Fresh native commands, compiler identity and both current ELF hashes/sizes are
in [the native follow-up](native-verification.md#hu06-startup-budget-correction-and-re-verification-2026-10-18).
The seventeen packaged cases executed in their intended JVM variants and again
in their native variants (thirteen productive plus four verification cases).
The productive reports' four guarded fixture skips are covered by those separate
runs. Invalid budgets were checked by starting independent processes, without
sending an address request; valid offline operation and all outbox modes also
passed. Source hashes are retained in `build/hu06-evidence/budget-fix-source.json`.

### Current OpenSpec verification report

| Dimension | Result |
| --- | --- |
| Completeness | 34/34 tasks; 48 ADDED/MODIFIED requirements mapped; no removed/renamed requirements |
| Correctness | All 48 requirement mappings and 90 scenarios reviewed against implementation/tests; current JVM, packaged and native evidence passed |
| Coherence | Design and repository patterns followed; pure Domain/inward dependency tests passed; eager budget validation restored |

The earlier critical startup finding is resolved by the current source and
three passing invalid-budget process cases in both JVM and native. No critical
issues, warnings or suggestions remain; no applicable verification check was
skipped. The change is ready for review and archive. Archiving is a separate
operation.

## Initial implementation execution record

The initial implementation passed the following stages before the later startup
regression. Its four fixture-only process cases intentionally skipped by each
productive artifact executed in its separate verification variant.

| Stage | Result | Evidence |
| --- | --- | --- |
| Focused JVM/PostgreSQL | 107 tests, zero failures/skips before final codec compatibility regression | `focused-profiles` run |
| Full clean JVM build / production isolation | 483 tests, zero failures/skips; build and isolation passed | `build/hu06-evidence/jvm/` |
| Packaged JVM process | 11 productive cases passed; four fixture-only cases passed separately in the verification artifact | `build/hu06-evidence/packaged-jvm/` |
| Final codec / boundary / HTTP / repository regression | 24 tests passed, zero failures/skips | `build/hu06-evidence/packaged-jvm/results/test/` |
| Final HU06 JVM process | Two cases passed, including current GET and collection schema | `build/hu06-evidence/packaged-hu06-final/` |
| Verification JVM artifact | Seven concurrency cases and four packaged fixture cases passed, zero failures/skips | `build/verification/hu06-evidence/verification-jvm/` |
| Mandrel native build / native process | Build passed; 11 productive cases passed, four fixture-only cases passed separately in the verification artifact | `build/hu06-evidence/native-process/` |
| Verification native artifact | Build passed; four packaged fixture cases passed, zero failures/skips | `build/verification/hu06-evidence/verification-native/` |
| Strict OpenSpec validation | Valid, zero issues | `openspec validate ... --strict --json` |

Reports are preserved per stage with `docs/operations/hu06-test-results.gradle`.
The verification JVM profile was rerun from the isolated source copy
`/tmp/hu06-isolated-01a150f1` after another Gradle execution in the shared checkout
removed compiled classes during discovery. Its successful reports were copied
back to the path above; the interrupted shared-directory run is not passing
evidence. The JVM test heap is 1 GiB: the expanded full suite exhausted Gradle's default
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
./gradlew --offline -Pverification=true -I docs/operations/hu06-test-results.gradle -Phu06EvidenceRun=verification-jvm test --tests '*AddressConcurrencyHttpTest' quarkusIntTest --tests '*PackagedVerificationIT'
./gradlew --offline quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
./gradlew --offline -I docs/operations/hu06-test-results.gradle -Phu06EvidenceRun=native-process quarkusIntTest -x quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
# Execute the verification-variant commands in the isolated source copy.
./gradlew --offline -Pverification=true quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
./gradlew --offline -Pverification=true -I docs/operations/hu06-test-results.gradle -Phu06EvidenceRun=verification-native quarkusIntTest --tests '*PackagedVerificationIT' -x quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
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

The other-child concurrency fixture tests the shared Company→Site protection,
revision, common audit and enabled Site-event protocol; it does not implement
or claim HU07 contact management.
Geographic acceptance is an observed contract result, without distributed
atomicity against later provider changes. Pagination observes current pages and
does not provide a cross-page snapshot. Outbox remains stored PENDING; HU08
publication and broker delivery are outside HU06.

Actual productive JVM and native processes passed `PackagedAddressIT`, with
independent PostgreSQL and HTTP fixtures outside the artifact. Native commands,
compiler identity, both ELF hashes/sizes and the exact executed scope are recorded
in [native verification](native-verification.md#hu06-productive-native-verification-2026-10-18).
The current productive native suite passed thirteen cases and the separate
verification native suite passed four, with zero failures. Eager HU06 composition
is retained for zero/negative/overflow invalid-budget startup rejection.

The source copy used for verification was compared against current production,
verification, packaged-test and contract sources before final tracking. No
behavioral drift remained. OpenSpec strict validation passed with zero issues.
The change is ready for review before archiving.
