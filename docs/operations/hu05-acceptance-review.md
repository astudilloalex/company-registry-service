# HU05 acceptance review — add-company-site-lifecycle-management

Date: 2026-10-18. JVM, SQL, HTTP concurrency and packaged JVM gates passed.
Native binary/process/image verification is in progress; it is not implied by JVM
success. No archive or production deployment is performed.

## Acceptance matrix

| AC | Behavior | Executed evidence |
| --- | --- | --- |
| AC01 | Partial closed PATCH; omission preserves HEADQUARTERS | SiteInformationPolicyTest, SiteMutationBoundaryTest, SiteMutationHttpTest; packaged replay |
| AC02 | Managed fields excluded; qualified identity/ownership | Metadata SQL insert/update constraints, repository isolation and HTTP 404 precedence |
| AC03 | Canonical Unicode before limits/signature | Pure information tests, independent HMAC vectors and accented packaged requests |
| AC04 | Activation under editable Company; ARCHIVED rejects | Sixteen-transition pure matrix, HTTP commands and both-order Company races |
| AC05 | Independent Site state and historical operativity | HTTP/JSONB tests and packaged no-op true replay after Company deactivation/archival |
| AC06 | Deactivation rules and legal headquarters protection | Pure/HTTP tests, compatible Company activation race and packaged lifecycle |
| AC07 | Terminal close; historical resource remains readable | Pure/HTTP commands, redundant close, packaged closure and original replay |
| AC08 | ACTIVE legal headquarters cannot be removed | Pure matrix, HTTP close/deactivate/reclassification and both-order Company races |
| AC09 | One non-CLOSED headquarters; own reservation excluded | SQL constraints, repository diagnostics and HTTP Site/HU04 acquisition races |
| AC10 | Company activation and headquarters withdrawal coordinate | Both-order compatible test writers; no HU03 endpoint or remote Party eligibility claim |
| AC11 | Common Site revision, exact increment and stale rejection | Conditional bulk update, effective/no-op races and both-order child writer races |
| AC12 | Tenant/Company/Site scope before revision/state | Qualified repository tests, missing/foreign Site and HTTP precedence |
| AC13 | Four namespaces, presence, durable replay and rotation | Independent HMAC vectors, same/cross-Company key races, competitor rollback and packaged K1→K2/restart |
| AC14 | Atomic selected event and explicit uncertainty | Producer/schema tests, all outbox modes, injected failures/response loss, bounded cancellation/recovery and real packaged events |
| AC15 | No-op preserves Site version/audit; optional completion | Pure/HTTP/JSONB tests, both-order no-op race, no event on replay and packaged historical no-op |

The matrix records the suites establishing each claim. Exhaustive domain matrices
and independent SQL races execute on JVM; they are not described as exhaustive
native race coverage. The native harness targets actual serialization, persistence,
HTTP errors, event contracts, process restart/rotation and non-root execution.

## Executed gates and reports

| Gate | Command selection | Result |
| --- | --- | --- |
| Full JVM regression/build/isolation | `./gradlew -I docs/operations/hu05-test-results.gradle -Phu05EvidenceRun=final-jvm test build verifyProductionIsolation` | BUILD SUCCESSFUL, 5m48s; 94 suites, 313 tests, 0 failures/errors |
| Expanded HU05 cases | `test --tests '*SiteMutationDeadlineTest' --tests '*SiteMutationConcurrencyHttpTest' --tests '*SiteMutationBoundaryTest' --tests '*SiteMutationBudgetTest'` with `hu05EvidenceRun=packaged-jvm` | 4 suites, 20 selected tests, 0 failures/errors; includes eight concurrency cases and exact catalog mapping |
| Packaged JVM suite | Same invocation followed by `quarkusIntTest` | BUILD SUCCESSFUL, total invocation 3m44s; 6 suites, 11 process tests, 0 failures/errors |

Database-backed commands use
`DOCKER_HOST=unix:///run/user/1000/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true`.
The selected HU05 tests overlap the full regression; their counts are not summed
as unique tests. Reports are under `build/hu05-evidence/final-jvm/results/` and
`build/hu05-evidence/packaged-jvm/results/`. An earlier isolated selection passed
21 tests before the final competitor-rollback/deadline additions.

A prior non-isolated run could not finalize Gradle's binary report because
`build/test-results/test/binary/in-progress-results-generic.bin` disappeared. It
is not counted as passing evidence. The isolated rerun and later gates passed.

## Persistence, compatibility and artifacts

V1–V4 migration sources were checked unchanged with git diff. PostgreSQL 18 clean
startup, repeated Flyway validation/history, six application tables, runtime DML
without DDL, metadata insertion/update rejection, and V4 SQL upgrade all passed.
Incompatible HU05 history stops with controlled 23514 diagnostics; no migration
rewrites snapshots, fabricates provenance or deletes acceptances.

HU01/HU02/HU04 fingerprints and result formats remain unchanged and passed their
regressions. HU05 JSONB round-trips cover all four operations and no-op, strict
shape/types/ownership/revision, original Company status and boolean, UTC microseconds
and completion audit independent of unchanged Site audit.

The productive JVM artifact includes OpenAPI and updated/closed schemas/examples,
with no verification API classes. The packaged process runs from a temporary working
directory with providers unavailable, retains PostgreSQL across restart and K1→K2,
and replays all operations/no-op across outbox modes. Original true operativity is
retained after Company deactivation/archival. Actual updated/closed rows satisfy
Draft 2020-12 schemas and cross-field identity/time/revision/actor invariants.

## Compatible writer fixtures and limits

SiteMutationConcurrencyHttpTest owns test-only Company activation/archival and
address/contact writers. They lock qualified Company → Site; child writers compare
the common Site revision, insert the child and increment Site once atomically.
Barrier-only connections may hold Site to observe a competing lock wait; they perform
no business mutation. The tests establish the local protocol, not actual HU03/HU06/
HU07 endpoints, remote Party eligibility or distributed provider atomicity. None of
these writers enters production sources. The same limits are in persistence-contract.md.

## Native gate pending

The binary build uses the documented Mandrel JDK 25/Podman builder with 5 GB and two
threads. Output is `build/hu05-evidence/native-build.log`. Native process and OCI
results, commands and artifact identities must be recorded after execution in
native-verification.md. The OCI harness `verify-hu05-native-image.py` prepares an
actual V4 native startup followed by V5 upgrade, retained historical acceptance and
checksums, four HU05 operations/no-op, true historical boolean, K1→K2 and all modes
under UID 1001. Harness preparation alone is not execution evidence.
