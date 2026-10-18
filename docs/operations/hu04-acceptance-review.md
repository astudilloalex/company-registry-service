# HU04 acceptance review — issue #6

Change: `add-company-site-registration-and-retrieval` (spec-driven).
Review date: 2026-10-17, America/Guayaquil.

## Execution status

The focused PostgreSQL 18, HTTP, concurrency, rollback and JVM restart checks
listed below have passed. Final regression, the scalar parent-state coherence
check and native/OCI verification are pending. This record does not yet claim
complete HU04 acceptance. Replace this status only with executed results.

## Acceptance traceability

| AC | Executed verification |
| --- | --- |
| AC01 | `SiteRegistrationPolicyTest`, `SiteRegistrationTest`, `RegisterSiteTest`: four exact types, omitted BRANCH, DRAFT/0, audit and DRAFT/ACTIVE/INACTIVE parents |
| AC02 | `RegisterSiteTest`, `SiteHttpTest`: absent/alien Company, own parent without Site, qualified tenant/Company/Site failures |
| AC03 | `SiteConcurrencyHttpTest`: canonical code contenders; `SiteHttpTest`: CLOSED code reservation (final rerun pending) |
| AC04 | Cross-Company key race and scoped uniqueness in `SiteConcurrencyHttpTest`; different-key same-code acceptance in `SiteHttpTest` (final rerun pending) |
| AC05 | HEADQUARTERS conflict in `RegisterSiteTest`; DRAFT/ACTIVE/INACTIVE reservation in `SiteHttpTest` (final rerun pending) |
| AC06 | `SiteConcurrencyHttpTest`: independent HTTP requests/transactions, observed PostgreSQL lock barriers, one acceptance and enabled event |
| AC07 | `SiteHttpTest`: historical CLOSED headquarters keeps its code and allows replacement with a free code (final rerun pending) |
| AC08 | `SiteRegistrationPolicyTest`, `SiteFingerprintTest`, `SiteHttpTest`: accents, significant inner spaces, Unicode code points and uppercase expansion |
| AC09 | `SiteConcurrencyHttpTest`: archival wins/registration wins/holder rolls back, all four Site types, no lifecycle endpoint |
| AC10 | `SiteFingerprintTest`, `SiteResultMappingTest`, `RegisterSiteTest`, `PackagedSiteIT`: original acceptance, SQL collision recovery, lost commit response, real JSONB, restart/rotation/mode/actor changes |
| AC11 | `SiteHttpTest`, `SiteCursorTest`: qualified historical reads, AND filters, authenticated collection scope and complete timestamp/UUID boundary |
| AC12 | `SiteRegistrationTest`: all 16 state combinations; `SiteHttpTest`: ACTIVE Site under ARCHIVED Company; `SiteReadCoherenceTest` pending for a parent changed between existence and join |
| AC13 | `SiteCreatedContractTest`, `SiteCreatedAtomicTest`, `RegisterSiteTest`, `PackagedSiteIT`: real event, closed Draft 2020-12 schema, modes, PENDING counters and joint rollback |
| AC14 | `RegisterSiteTest`: full CompanyRecord equality after each type/state creation; OCI harness also compares the complete Company row before/after creation (OCI pending) |

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
QUARKUS_HTTP_TEST_PORT=0 DOCKER_HOST=unix:///run/user/1000/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew test build quarkusIntTest verifyProductionIsolation --console=plain
openspec validate add-company-site-registration-and-retrieval --strict
```

Focused successful suites and earlier full-run output are under
`build/hu04-evidence/`; Gradle XML reports are under `build/test-results/`.
`PackagedSiteIT` has passed against the productive JVM artifact, retaining its
database across subprocess restarts and verifying no provider requests.

Native build/process/OCI evidence is pending. The commands use the documented
5 GiB heap/two-thread configuration in [native-verification.md](native-verification.md).
The OCI harness is [verify-hu04-native-image.py](verify-hu04-native-image.py),
which owns and removes its disposable containers. It checks UID 1001, V4,
typed Site results, cursor continuation, root errors, original event payload,
restart/key rotation, preserved Company data and refused providers/broker.

No HU03/HU05 routes, production fixtures or HU08 publisher are introduced.
Do not infer native acceptance from JVM success. Preserve the final evidence
before cleaning build outputs.
