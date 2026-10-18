# HU03 acceptance review

Change: `add-company-lifecycle-management`. Review date: 2026-10-18
(America/Guayaquil). Local implementation and verification; no production rollout.

## Acceptance evidence

| Criteria | Executable evidence | Result |
| --- | --- | --- |
| AC01–AC02 | CompanyLifecyclePolicyTest, CompanyActivationPolicyTest, CompanyLifecycleMappingTest, CompanyLifecycleHttpTest | Passed in the complete JVM/PostgreSQL regression |
| AC03–AC04 | CompanyLifecycleMappingTest resolves only current headquarters; CompanyLifecycleHttpTest checks absent/inactive/closed history/replacement without children | Passed in the complete JVM/PostgreSQL regression |
| AC05–AC06 | Real Party HTTP stub, exact context propagation, minimal eligibility, absence/kind/state/contract/transport failures and LifecycleRecoveryTest shared deadline/cancellation | Passed in the complete JVM/PostgreSQL regression |
| AC07–AC08 | CompanyLifecycleHttpTest full cycle/current Site operativity/original Site no-op replay; unchanged Site/address/contact snapshots | Passed in the complete JVM/PostgreSQL regression |
| AC09–AC10 | Actual archive routes reject HU02/HU03/HU04/HU05 including no-op; CompanyLifecycleConcurrencyHttpTest covers every Site type/writer | Concurrency matrix passed; complete JVM/PostgreSQL regression passed |
| AC11 | ordered real HTTP archives and effective/no-op HU02 PATCH, independently observed SQL row waits and fresh revision reads | Passed |
| AC12 | Namespace/vector/key rotation tests, JSONB codec, equivalent/different/cross-Company key races and rollback, packaged/native restart checks | All key races including competitor rollback passed; packaged JVM checks passed; native process checks in progress |
| AC13 | Three operations × three outbox modes × optional key; rollback after each mandatory write; real events/schema/envelope/archival producer tests | LifecycleRecoveryTest and contract suites passed; native/OCI checks in progress |
| AC14 | Actual activate vs headquarters deactivate/close/reclassification in both orders; actual archive vs HU04/HU05 writers for all four Site types; held Party allows Company/Site writes | Passed |

Concurrency tests use independent HTTP requests/connections and observed SQL/advisory
lock barriers, rather than elapsed sleeps. Company acceptance first protects the
active headquarters; removal first makes activation fail. Archive first rejects
every new Site writer with its original catalog; Site first precedes archive without
incrementing Company. The complete matrix is in CompanyLifecycleConcurrencyHttpTest.
Held Party cases establish both no retained local lock and revalidation when
Company.version is unchanged, and replay/current revision precedence over remote failure.

## Verification runs

Use `DOCKER_HOST=unix:///run/user/1000/podman/podman.sock`,
`TESTCONTAINERS_RYUK_DISABLED=true` and `QUARKUS_HTTP_TEST_PORT=0` for isolated test
containers and dynamic HTTP ports. Reports are isolated with
`docs/operations/hu03-test-results.gradle`; they must not be overwritten by another stage.

Executed initial stages: 39 persistence/SQL tests, 45 fingerprint/codec/JSONB and
regression tests, and 18 HTTP/recovery/OpenAPI/startup/signature tests passed with
zero failures/errors/skips. The first full 379-test run had nine fixture failures:
raw requests without JSON Content-Type and a restricted-resource application
restart that reused a closed Hibernate factory. LifecycleHttpProfile now shares one
isolated database/provider application for both HU03 HTTP classes. That run's
other 370 cases passed, including all prior capabilities and the actual AC11/AC14
matrices. The corrected complete run passed all 379 cases with zero failures, errors
or skips, plus build and verifyProductionIsolation, in 6m20s. Its reports are in
`build/hu03-evidence/final-jvm/results/test/` and log in
`build/hu03-evidence/final-jvm.log`.

Executed JVM command and remaining process stages:

```shell
./gradlew -I docs/operations/hu03-test-results.gradle -Phu03EvidenceRun=final-jvm test build verifyProductionIsolation
./gradlew -I docs/operations/hu03-test-results.gradle -Phu03EvidenceRun=packaged-jvm test --tests '*LifecycleRecoveryTest' --tests '*LifecycleFingerprintV1Test' quarkusIntTest verifyProductionIsolation
./gradlew quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
./gradlew -I docs/operations/hu03-test-results.gradle -Phu03EvidenceRun=native-process quarkusIntTest -x quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false
podman build -f src/main/docker/Dockerfile.native-micro -t company-registry-service:hu03-native .
EVIDENCE_DIR=build/hu03-evidence/oci python3 -u docs/operations/verify-hu03-native-image.py
openspec validate add-company-lifecycle-management --strict
```

The productive packaged JVM run passed in 4m2s: 14 focused budget/fingerprint tests
and all nine productive process cases, including PackagedCompanyLifecycleIT. Four
PackagedVerificationIT cases are deliberately disabled by `verification.artifact=false`:
these probe routes are absent from the productive artifact. They are not HU03 gates
and are not counted as executed productive coverage. Reports are in
`build/hu03-evidence/packaged-jvm/results/`.

The expanded final HTTP matrix passed all 16 cases in 2m18s, including registration
of every Site type against actual archive in both orders and HU04 replay after the
actual Company cycle/archive. Reports: `build/hu03-evidence/final-http-races/results/test/`.

The final source review connected the Domain transition decision explicitly to the
accepted state and introduced a void guard for already-held opaque audit values,
retaining the original required-value behavior. A repeated full regression on that
source executed 379 cases: 378 passed and one existing HU05 SQL-barrier observer
exceeded its five-second wait. The entire unchanged SiteMutationConcurrencyHttpTest
suite then passed all eight cases in isolation; build, production isolation and all
nine productive packaged cases also passed in 3m44s. No invariant assertion was
removed or relaxed. Evidence is in `final-quality/` and `hu05-race-recheck/`.

The HTTP contract review added the three existing context error codes to the HU03
OpenAPI enum and verifies missing/duplicate/invalid headers before replay. The
reader distinguishes an absent body from an invalid whitespace-only JSON document.
The final contract stage passed all 12 affected HTTP/OpenAPI/signature tests and
all nine productive packaged cases in 4m28s. Its reports are in `final-contract/`.
The HU05 barrier observer now permits eight seconds within the unchanged ten-second
application budget, retaining the same SQL condition and assertions; the final full
regression will verify that test-only timing adjustment. Earlier native builds were intentionally stopped
when these final source/contract corrections were made; their cancellation logs
are not counted as completed native evidence.

OpenSpec strict validation already passed during implementation; it will be
repeated after every task is complete. PackagedCompanyLifecycleIT checks the actual
productive JVM/native process with external HTTP/SQL, five transitions, minimal
Party, null snapshots, real schema-validated events and restart/rotation/mode replay.
The OCI harness prepares authentic V5 Flyway history and actual HU01/HU02/HU04/HU05
acceptances before V6, checks all six tables/checksums and replays after upgrade,
then verifies all three modes with UID 1001, independent provider and explicit
external runtime configuration. Preparation alone is not a passing result.

## Limits and compatible rollback

Exhaustive race evidence is JVM/PostgreSQL evidence. Native and OCI process checks
exercise real transitions/results/events but do not establish an exhaustive native
race matrix. Party is observed for each new activation, without distributed atomicity
or subsequent synchronization. Claims/leases/publication remain HU08; no broker is
required. Timeout/uncertain commit is 503 without asserting rollback. Recovery
starts only after acknowledged finalization and reads an acceptance without retrying
a write; requests without a key cannot promise an original replay.

Rollback must retain V6, all six tables, identities/reservations, confirmed
Companies/Sites/children, completions/events and original key/policy/format versions.
Restore compatible code or suspend unsupported routes. Do not undo migrations,
delete history, replace historical key material or reset aggregate versions.
