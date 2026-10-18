# HU05 acceptance review — add-company-site-lifecycle-management

Date: 2026-10-18. This record distinguishes implementation from executed evidence.
The change is not accepted or ready for archival until the pending checks below run.

## Acceptance matrix

| AC | Implemented behavior | Evidence / remaining verification |
| --- | --- | --- |
| AC01 | Closed partial PATCH; omitted type preserves current classification | SiteInformationPolicyTest, SiteMutationHttpTest; packaged/native pending |
| AC02 | Managed identity/property/audit excluded; scoped SQL ownership | SiteMutationMetadataIntegrityTest, SiteMutationPersistenceTest, HTTP foreign Site; packaged/native pending |
| AC03 | Strip/uppercase before Unicode limits and fingerprint | SiteInformationPolicyTest, SiteMutationFingerprintTest; packaged/native pending |
| AC04 | Site activation under editable Company | Pure 16-transition matrix and HTTP activation; concurrent/native pending |
| AC05 | Independent Site ACTIVE with historical false operativity | SiteMutationHttpTest and JSONB codec tests; restart/native pending |
| AC06 | ACTIVE→INACTIVE with legal headquarters protection | SiteLifecyclePolicyTest and SiteMutationHttpTest; concurrent/native pending |
| AC07 | Terminal closure from permitted states; historical GET/replay | Pure matrix, HTTP closure/redundancy/replay; packaged/native pending |
| AC08 | Active legal headquarters cannot be removed | SiteLifecyclePolicyTest and HTTP close/deactivate/reclassification; Company race/native pending |
| AC09 | One open HEADQUARTERS, excluding own Site | SQL constraints and scoped repository diagnostics; HTTP race pending |
| AC10 | Company activation and headquarters withdrawal coordinate | Test-only Company writer races prepared; execution pending |
| AC11 | Expected Site revision and one conditional increment | SiteMutationPersistenceTest; HTTP/child races prepared, execution pending |
| AC12 | Tenant/Company/Site isolation and ownership precedence | Repository and SiteMutationHttpTest; full regression/native pending |
| AC13 | Four idempotent namespaces, original intent and historical keys | Deterministic independent HMAC vectors and rotation tests; races/restart/native pending |
| AC14 | Atomic selected outbox event and explicit uncertainty | Producer/schema tests and injected rollback/response-loss tests; expanded modes/packaged/native pending |
| AC15 | Canonical no-op preserves Site revision/audit and optional completion | Pure/HTTP/JSONB tests; no-op race/restart/native pending |

## Executed targeted checks

The following targeted Gradle selections returned BUILD SUCCESSFUL before the full
regression gate. They are not a replacement for the packaged/native gates:

- Site information and unchanged HU04 registration policy tests.
- Site lifecycle policy and ArchitectureTest.
- Neutral mutation command/result tests and ArchitectureTest.
- PostgreSQL 18 clean V5 startup, repeated validation/runtime privileges, metadata
  insert/update constraints for all four namespaces, scoped Site references.
- V4 SQL upgrade with earlier acceptances/events and incompatible HU05 history,
  catalog/documentation checks and existing Site integrity suite.
- Qualified Site locking/update, exact revision, parent/creation audit preservation,
  code release at commit/rollback, failure classification and HU04 regression.
- Independent HU05 HMAC vectors and historical HU01/HU02/HU04 fingerprint regression.
- HU05 codec strict shape/revision/ownership/audit validation and JSONB round-trip,
  including all four operations and no-op; earlier codecs remain unchanged.
- SiteMutationHttpTest plus ArchitectureTest: PATCH, no-op, all four routes, replay
  after closure/archival, legal headquarters protection, header/body diagnostics.
- Updated/closed producer and Draft 2020-12 schema tests, OpenApiHttpTest, HU04 HTTP.
- MutateSiteTest injected failures after Site/completion/event/before commit,
  durable recovery after response loss and explicit unkeyed uncertainty.

One concurrency/budget test run could not finalize Gradle's binary report because
`build/test-results/test/binary/in-progress-results-generic.bin` disappeared.
That run is not counted as passing evidence. Subsequent verification uses
`hu05-test-results.gradle` and isolated `build/hu05-evidence/<run>/` reports.

V1–V4 migration sources were checked unchanged with git diff. V5 adds one CHECK and
no application table. Runtime remains DML-only with a separate migration role.

## Compatible writer fixtures and limits

SiteMutationConcurrencyHttpTest owns test-only Company activation/archival and
address/contact writers. They lock qualified Company → Site and share the Site
expected revision for child changes. Barrier-only connections can deliberately hold
Site to observe another transaction's lock wait; they perform no business mutation.
These fixtures establish only the local protocol, not Party eligibility or actual
HU03/HU06/HU07 endpoint integration. No fixture is added to production sources.

The packaged Site suite is extended to accept/replay every HU05 operation and no-op
across retained PostgreSQL, independent processes, historical K1→K2 and all outbox
modes, with providers unavailable. It validates real updated/closed events. The OCI
harness extends HU04 verification with HU05 mutations/replay and UID 1001 checks.
Preparation of these harnesses does not establish execution.

## Remaining gates

- Isolated concurrency/budget/mode/boundary verification and both-order Company and
  child writer races; full JVM regression.
- `./gradlew build`, `quarkusIntTest` and `verifyProductionIsolation` against the
  final JVM artifact, including all prior workflow regressions.
- Native binary build/process/restart suite and non-root Podman image execution,
  recording actual commands, results and artifact hashes in native-verification.md.
- Final OpenSpec progress/validation and completion review. No archive is performed.
