# HU04 acceptance review — issue #6

Change: `add-company-site-registration-and-retrieval` (spec-driven),
[HU04 / issue #6](https://github.com/astudilloalex/company-registry-service/issues/6).
Review date: 2026-10-17, America/Guayaquil.

## Execution status

PostgreSQL 18, HTTP, concurrency, rollback, scalar parent-state coherence,
full JVM regression, packaged restart and production isolation checks passed.
The productive native executable and UID 1001 OCI image also passed their
executed checks. AC01–AC14 and the additional scenarios below have evidence;
no required HU04 verification remains pending.

| Suite | Executed | Failures/errors | Skipped |
| --- | --- | --- | --- |
| `test` (77 suites) | 254 | 0/0 | 0 |
| JVM `quarkusIntTest` (6 suites) | 7 | 0/0 | 4 existing verification-fixture cases excluded from the productive artifact |
| `verifyProductionIsolation` | passed | 0 | 0 |
| Native `quarkusIntTest` (6 suites) | 7 | 0/0 | 4 existing verification-fixture cases excluded from the productive artifact |
| Native OCI harness | 3 modes plus restart/rotation | 0 | 0 |

## Acceptance traceability

| AC | Executed verification |
| --- | --- |
| AC01 | `SiteRegistrationPolicyTest`, `SiteRegistrationTest`, `RegisterSiteTest`: four exact types, omitted BRANCH, DRAFT/0, audit and DRAFT/ACTIVE/INACTIVE parents |
| AC02 | `RegisterSiteTest`, `SiteHttpTest`: absent/alien Company, own parent without Site, qualified tenant/Company/Site failures |
| AC03 | `SiteConcurrencyHttpTest`: canonical code contenders; `SiteHttpTest`: CLOSED code reservation |
| AC04 | Cross-Company key race and scoped uniqueness in `SiteConcurrencyHttpTest`; different-key same-code acceptance in `SiteHttpTest` |
| AC05 | HEADQUARTERS conflict in `RegisterSiteTest`; DRAFT/ACTIVE/INACTIVE reservation in `SiteHttpTest` |
| AC06 | `SiteConcurrencyHttpTest`: independent HTTP requests/transactions, observed PostgreSQL lock barriers, one acceptance and enabled event |
| AC07 | `SiteHttpTest`: historical CLOSED headquarters keeps its code and allows replacement with a free code |
| AC08 | `SiteRegistrationPolicyTest`, `SiteFingerprintTest`, `SiteHttpTest`: accents, significant inner spaces, Unicode code points and uppercase expansion |
| AC09 | `SiteConcurrencyHttpTest`: archival wins/registration wins/holder rolls back, all four Site types, no lifecycle endpoint |
| AC10 | `SiteFingerprintTest`, `SiteResultMappingTest`, `RegisterSiteTest`, `PackagedSiteIT`: original acceptance, SQL collision recovery, lost commit response, real JSONB, restart/rotation/mode/actor changes |
| AC11 | `SiteHttpTest`, `SiteCursorTest`: qualified historical reads, AND filters, authenticated collection scope and complete timestamp/UUID boundary |
| AC12 | `SiteRegistrationTest`: all 16 state combinations; `SiteHttpTest`: ACTIVE Site under ARCHIVED Company; `SiteReadCoherenceTest`: parent changed by a separate connection between existence and join, both detail and page |
| AC13 | `SiteCreatedContractTest`, `SiteCreatedAtomicTest`, `RegisterSiteTest`, `PackagedSiteIT`: real event, closed Draft 2020-12 schema, modes, PENDING counters and joint rollback |
| AC14 | `RegisterSiteTest`: full CompanyRecord equality after each type/state creation; OCI harness also compares the complete Company row before/after creation |

## Additional checks

- `SiteCreationMetadataIntegrityTest`: invalid HU04 metadata on insert/update,
  hash/snapshot checks and composite owner references.
- `SiteCreationMetadataMigrationTest`: upgrade from V3, preserved Company/Site,
  HU01/HU02 snapshots and event, controlled incompatible-history rejection.
- `DatabaseStartupTest`, `SchemaCatalogIntegrityTest`, `SchemaDocumentationTest`,
  `SiteIntegrityTest`: six application tables, catalog, old migrations intact,
  scoped indexes/constraints, concurrent headquarters and runtime DML versus DDL.
- `SiteBudgetTest`: positive finite budget, decreasing checkpoints through fresh
  recovery, timeout, cancellation and PostgreSQL 40001/40P01 aborts. Cancellation
  returns through rollback before session closure, with no detached write.
- `RegisterSiteTest`: failure after Site/completion/event and before commit leaves
  no rows; the key can be reused. Lost response after actual commit recovers its
  durable result in a fresh session.
- `SiteBoundaryTest`, `SiteHttpTest`, `OpenApiHttpTest`: original structured input,
  full-document precedence, specific status/code shape, published/embedded contract
  and compatibility with the shared response library.
- Historical Company fingerprint/codecs remain unchanged. The full regression
  includes HU01/HU02 and architecture checks.

## Commands and evidence

Run from the repository root with Java 25 and the installed OpenSpec CLI on PATH.
The test HTTP port is assigned dynamically because 8081 is occupied locally.

```sh
QUARKUS_HTTP_TEST_PORT=0 DOCKER_HOST=unix:///run/user/1000/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew -I docs/operations/hu04-test-results.gradle test build quarkusIntTest verifyProductionIsolation --console=plain
openspec validate add-company-site-registration-and-retrieval --strict
```

The complete successful command is recorded in `build/hu04-evidence/jvm-accepted.log`
(3m 59s). XML reports are under `build/hu04-evidence/jvm/results/{test,quarkusIntTest}/xml`;
counts are recorded in `build/hu04-evidence/jvm/results-summary.json`.
The init script isolates generated report directories after Gradle's default
binary-result store failed with EOF/missing-file errors. It changes no test
selection, assertions, application sources or artifact configuration.
Focused successful suites and earlier attempts are retained under
`build/hu04-evidence/`; `read-coherence-isolated.log` records the separate
coherence check. Deprecation notices from the existing Gradle build and expected
negative-case SQL diagnostics are not compiler/test failures. The cancellation
check passes without the live-transaction-close diagnostic.
`PackagedSiteIT` has passed against the productive JVM artifact, retaining its
database across subprocess restarts and verifying no provider requests.

## Native executable and OCI evidence

The commands use the documented 5 GiB heap/two-thread configuration in
[native-verification.md](native-verification.md). The build completed in 6m;
native generation took 5m 1s with 4.66 GB peak RSS. Mandrel 25.0.4.1-Final used
Java 25.0.4.1+1-LTS, optimization level 2 and x86-64-v3. Native process integration
completed successfully in 55s, including `PackagedSiteIT` and HU01/HU02 regression.

- Runner: `build/company-registry-service-1.0.0-SNAPSHOT-runner`.
- Runner SHA-256: `851c5e175215d0843435b89c82eef94548b31b81d9a5cb840e82cb8f7cccd187`.
- Image: `localhost/company-registry-service:hu04-native`.
- Image ID: `7c32f881f71aaf3a03b45b88a798bf51cfc8f2a4f4abd1b9083068d85ec68664`.
- Configured and executed container UID: `1001`.

Evidence paths under `build/hu04-evidence/`:

- `native-build.log`, `native-process.log`, `native-image-build.log` and
  `native-image-verification.log` retain the executed commands' full output.
- `native/results/quarkusIntTest/xml` contains native process reports;
  `native/results-summary.json` records counts, runner/image identity and SHA-256
  equality of V1–V3 against their Git originals.
- `oci/report.json`, per-mode process/log files and original event JSON retain
  the OCI results. The runner hash in that report matches the executable above.

The OCI refusal probe observed errno 111 at `127.0.0.1:9`. Each mode created one
Company, two Sites and two completions. Disabled retained zero events; stored-only
and published retained two PENDING events with attempts/technical version zero.
Disabled → published replay after restart/rotation/closure/archival retained zero
events, proving that replay does not backfill an event. Other modes replayed after
changing to disabled without changing the original event counts or snapshots.
The real native events were validated against Draft 2020-12 in `PackagedSiteIT`;
the OCI harness also checked the eight-field payload and identity invariants.

Native build output includes notices about a duplicate generated PanacheEntity
entry and deprecated proxy-configuration metadata. They are retained in the full
log; binary generation and all binary/image checks passed.

The OCI harness is [verify-hu04-native-image.py](verify-hu04-native-image.py),
which owns and removes its disposable containers. It checks UID 1001, V4,
typed Site results, cursor continuation, root errors, original event payload,
restart/key rotation, preserved Company data and refused providers/broker.

No HU03/HU05 routes, production fixtures or HU08 publisher are introduced.
Native acceptance above comes from executed binary/image checks. Preserve the
final evidence before cleaning build outputs.
