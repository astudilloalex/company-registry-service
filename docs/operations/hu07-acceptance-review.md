# HU07 acceptance review

Change: `add-company-site-contact-management`, spec-driven, reviewed 2026-10-18
(America/Guayaquil). This record distinguishes executed evidence from pending
verification. No issue text or earlier migration checksum is rewritten.

## Contract decision

The approved EMAIL V1 contract supersedes T00/HU07 AC07 local-case preservation:
ASCII validation precedes full Locale.ROOT lowercase; dot/plus aliases remain
significant; limits are 254 total and 64 local. Live incompatible data or
lowercase collisions stop V8 without automatic remediation. Deleted historical
bytes, opaque snapshots and actors retain their original values.

## Acceptance trace

| Criteria | Concrete coverage |
| --- | --- |
| AC01–AC07 | ContactValueTest, ContactInformationPolicyTest, ContactBoundaryTest and ContactHttpTest: four channels, ASCII/lowercase, alias/length boundaries, fixed/ambiguous mobile, canonical labels and PATCH presence. |
| AC08–AC10 | ContactPrimaryPolicyTest, ContactRepositoryTest, ContactHttpTest and ContactConcurrencyHttpTest: explicit destination replacement, zero selection, reclassification, preserved creation and common audit. |
| AC11 | ContactIntegrityTest, ContactRepositoryTest and ContactHttpTest: canonical live duplicates, channel independence, duplicate-before-demotion and deleted history reuse. |
| AC12–AC13 | ContactConcurrencyHttpTest: independent protected transactions and real HTTP races, one shared Site revision, Site information/lifecycle, Company archival, other-child updates and coherent current reads. |
| AC14 | ContactAcceptanceTest, ContactCursorTest, MutateContactTest, ContactRepositoryTest and PackagedContactIT: fixed bytes/HMAC, independent presence, value-only PATCH, JSONB, retained keys, original snapshots, response loss/recovery and process restart. |
| AC15–AC16 | ContactHttpTest, ContactRepositoryTest and PackagedContactIT: minimal deletion receipt, retained history, live detail/page exclusion, complete scope, revision/state precedence and cursor boundary. |
| AC17 | ContactConcurrencyHttpTest and PackagedContactIT: rollback fault injection, all outbox modes, one minimal Site event per acceptance and no event for no-op/replay. |

## Commands and evidence

Use the documented rootless Podman socket:

```shell
export DOCKER_HOST=unix:///run/user/1000/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
export QUARKUS_HTTP_TEST_PORT=0
./gradlew -I docs/operations/hu07-test-results.gradle -Phu07EvidenceRun=jvm test build verifyProductionIsolation
./gradlew -I docs/operations/hu07-test-results.gradle -Phu07EvidenceRun=jvm-process quarkusIntTest
./gradlew -I docs/operations/hu07-test-results.gradle -Phu07EvidenceRun=contact-final test --tests '*Contact*Test' build verifyProductionIsolation
./gradlew -I docs/operations/hu07-test-results.gradle -Phu07EvidenceRun=contact-process-final quarkusIntTest --tests '*PackagedContactIT'
./gradlew -Pverification=true -I docs/operations/hu07-test-results.gradle -Phu07EvidenceRun=verification-jvm quarkusIntTest --tests '*PackagedVerificationIT'
```

Each run preserves separate XML/HTML reports under
`build/hu07-evidence/<run>/results/<task>/` (the verification variant uses
`build/verification/hu07-evidence/`). Native commands, artifact identity and
actual native process results are recorded in `native-verification.md`.

The final full JVM/PostgreSQL run passed: 550 cases in 138 suites, with zero
failures/errors/skips. `test build verifyProductionIsolation` completed in
7m22s. Reports are in `build/hu07-evidence/jvm/results/test/`; the full log is
`build/hu07-evidence/jvm.log`. Source hashes and JVM artifact identity are in
`build/hu07-evidence/source-full-jvm-sha256.json` and `jvm-artifacts.json`.

Initial focused Domain/architecture and PostgreSQL/HTTP/acceptance suites passed.
The first database attempt used an inaccessible default Docker socket; switching
to the README's Podman socket resolved provisioning. The initial HTTP fixture
used disabled outbox; its profile now explicitly selects stored-only before
asserting stored events. Neither unsuccessful attempt is acceptance evidence.

Initial complete runs identified one stale V7 expectation and one test fixture
that reused tenant/operation/key across different resources. Both were corrected;
those unsuccessful runs are retained as `jvm-initial` and `jvm-fixture-review`.
The final clean JVM result above supersedes them.

Final contact verification after the fingerprint admission-order refinement
passed 76 cases in 16 suites, zero failures/errors/skips, followed by a fresh
productive build and isolation check (2m28s). Valid accepted CREATE bytes/hash
remain identical to the golden vector; key mismatch now precedes effective
MOBILE/channel validation. Reports/logs are under `build/hu07-evidence/contact-final/`
and `contact-final.log`. Final source hashes and JVM artifact identity are in
`source-sha256.json` and `jvm-artifacts-final.json`.

| External process stage | Actual result | Evidence |
| --- | --- | --- |
| Complete productive JVM suite | 17 executed, zero failures/errors; four verification-only cases intentionally skipped; 4m18s | `build/hu07-evidence/jvm-process/`, `jvm-process.log` |
| Final productive contacts JVM | Four executed, zero failures/errors/skips; 1m13s | `build/hu07-evidence/contact-process-final/`, `contact-process-final.log` |
| Separate verification JVM artifact | Four executed, zero failures/errors/skips; 1m2s | `build/verification/hu07-evidence/verification-jvm/`, `build/hu07-evidence/verification-jvm.log` |

The final targeted stage covers the changed contact code; retained HU01–HU06
regressions passed in the full suite. The source manifests distinguish those
runs rather than implying that every stage ran against the same artifact.

The productive native build passed in 7m48s and the real native process suite
passed in 1m3s: 17 productive cases, zero failures/errors; four verification-only
cases intentionally skipped. `PackagedContactIT` accounts for four executed
native cases. The separate verification JVM executed its four cases as recorded
above; a new separate verification-native artifact is outside this HU07 run.
Native artifact identity, commands, exact scenario scope and preserved reports
are recorded in [native verification](native-verification.md#hu07-productive-native-verification-2026-10-18).

Strict OpenSpec validation passed. Final source hashes match the native build
inputs, and the productive executable is preserved with its SHA-256 identity.
All 38 tracked implementation tasks are complete after recording this evidence.

The process suite uses PostgreSQL and test-only external fixtures; restarted
processes point Party/Geography at an inaccessible local port. Contact operations
perform no provider/channel calls. Native evidence establishes the documented
process scenarios; exhaustive fault injection/concurrency matrices are JVM
coverage. No OCI image publication, remote data remediation or broker delivery
is claimed by HU07.
