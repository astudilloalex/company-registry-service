# Packaged and native verification

## Variant/source separation

Production builds use `src/main`, output `build/`. Explicit `-Pverification=true`
also compiles `src/verification/java` into an independent output at
`build/verification/`. The production binary does not merely hide test routes via
a runtime flag: those classes are absent. `src/contractTest` holds shared external
assertions, provider stubs and isolated PostgreSQL provisioning. `integrationTest`
and `setExtraNativeTest` use those sources without `sourceSets.test.output`.
No packaged/native test depends on injecting or mocking a CDI bean inside an
already-built external process. `quarkusIntTest` explicitly launches profile `test`.

The following JVM commands have passed, discovering `PackagedFoundationIT` and
launching actual separate Java processes with isolated PostgreSQL 18 and URL-based
HTTP stubs:

```shell
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew quarkusIntTest
./gradlew -Pverification=true quarkusIntTest
```

This establishes source/classpath separation and JVM execution only. Native
build, process, database, HTTP, client and OCI evidence is recorded below only
after those checks actually run.

## Builder and metadata baseline (11.2)

Verified with Podman 5.4.2 on Linux amd64:

```shell
podman image inspect --format '{{.Id}} {{.Digest}} {{.Architecture}} {{.Os}} {{.RepoDigests}}' quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25
podman run --rm --entrypoint /bin/bash quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 -lc '"$JAVA_HOME/bin/native-image" --version'
```

- Image ID: `f046cb1da286f9afce3b5ebb7d8260f265ee1b7ce22a02d1d868f722d4f14f20`.
- Resolved digest: `sha256:bf41b71020672f9b8485ad465be896aaf0dfeb72d0a94aba936bee120c1c313c`.
- Architecture/OS: `amd64/linux`.
- `native-image 25.0.4.1`, OpenJDK Mandrel `25.0.4.1-Final`, build
  `25.0.4.1+1-LTS`. It matches the application's Java 25 bytecode baseline.

The application sets that builder and explicit Podman runtime. Only the concrete
Infrastructure `UuidV7Generator` is initialized at runtime so SecureRandom is
not seeded during image generation. Domain has no native-image annotations.
Quarkus extensions supply Hibernate/Flyway/Jackson/shared-library metadata;
provider DTOs are explicitly registered for reflection and numbering metadata is
included as a native resource. OpenAPI is copied into META-INF at resource
processing, and Flyway packages `db/migration`. JSONB uses the isolated mapper
documented in the persistence contract. These settings still require the native
execution checks below; configuration alone is not acceptance.

## External verification coverage prepared

`PackagedVerificationIT` now exercises the six actual reactive mappings and
aggregate/completion/outbox commit/rollback through the verification process;
explicit API DTO/envelopes, strict JSON/validation/headers/framework errors;
concurrent async context; and Party/country/confirmed-division clients against
external URL stubs, including absence, invalid JSON/contracts, 503 and read
timeouts. `PackagedRestartIT` starts a second process from an isolated temporary directory with
providers deliberately unavailable, compares persisted Flyway history, checks
runtime DML/DDL permissions, and verifies distinct UUIDv7 across independent
verification processes. JDBC in that suite is external test inspection only.

`./gradlew -Pverification=true quarkusIntTest` passed with these expanded suites
against a separate JVM process. This does **not** establish native execution of
the same contracts. The native branch additionally asserts ELF magic and an
executable runner before starting the second process.

## Native build attempts and resolution

The productive native build was attempted with the approved flags and explicit
resource limits:

```shell
./gradlew quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=3g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
```

Observed builder output:

- Mandrel `25.0.4.1-Final`, Java `25.0.4.1+1-LTS`, gcc `11.5.0`.
- Target `x86-64-v3`, two threads out of 12 available processors.
- Heap reported as 2.86 GB from `-Xmx3g`.
- Stage `[1/8] Initializing` completed in 17.6 seconds.
- The shell command exceeded its **900000 ms (15 minute)** execution timeout
  before a completed runner/build result was observed. This is a tool execution
  timeout, not evidence of a native-image compilation defect or confirmed OOM.
- After interruption, no builder container or native-image process remained.
  Only the Gradle daemon was present. `build/` contained native-image input
  sources but no completed root `*-runner` executable.

Before the attempt the host reported 15 GiB RAM, 2.9 GiB available, and 8.3 GiB
swap in use; after interruption it reported 5.1 GiB available and 11 GiB swap in
use. These observations explain the chosen limits but do not prove a specific
failure cause. No other user processes/containers were stopped.

### Retry with a 45-minute execution window

The user authorized retrying on the same machine with a longer window. The
identical command above was run with a **2700000 ms (45 minute)** tool timeout.
It terminated after **10 minutes 32 seconds**, before reaching that timeout:

```text
Terminating due to java.lang.OutOfMemoryError: Java heap space
The Native Image build process ran out of memory.
Process exited abnormally ... with exit code 3
BUILD FAILED in 10m 32s
```

This establishes that the explicitly configured **3 GiB compiler Java heap was
insufficient for this attempt**. It does not establish insufficient disk space,
an OS-level OOM kill, or that the host's total 16 GB RAM cannot build the service.
Increasing the execution timeout alone cannot resolve this specific heap limit.
Mandrel again reported Java 25.0.4.1, `-Xmx3g` and two compiler threads; its initial
stage completed in 21.5 seconds. No builder container remained after the failure.
The post-attempt snapshot was 3.7 GiB available RAM and 12 GiB swap used of 15 GiB.

The user then authorized increasing the compiler heap to 5 GiB, keeping two
threads and the 45-minute execution window. The results below supersede this
earlier blocker; the failed attempts remain recorded for reproducibility.

### Successful native builds with 5 GiB

```shell
./gradlew quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
./gradlew -Pverification=true quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
```

Both builds succeeded with the recorded Mandrel digest/version and x86-64-v3
target. `file` identified both runners as stripped ELF 64-bit x86-64 Linux
executables, dynamically linked with `/lib64/ld-linux-x86-64.so.2`.
`quarkus-artifact.properties` identifies `type=native`, Mandrel, Java 25.

| Variant | Output | Full Gradle build | Native generation | Peak compiler RSS | File size |
| --- | --- | --- | --- | --- | --- |
| Production | `build/company-registry-service-1.0.0-SNAPSHOT-runner` | 10m 22s | 9m 38s | 4.43 GB | 101.86 MB |
| Verification | `build/verification/company-registry-service-1.0.0-SNAPSHOT-runner` | 10m 36s | 9m 42s | 4.71 GB | 106.62 MB |

Runner SHA-256 at successful build:

- Production: `7b659bb991d16dbc37ac2f14ed75ec7f07382e218a8521969c800d792053049f`.
- Verification: `077c6a6b5a144183d7afcf5f477f15b1c9fdabfc7cae18cf4e82a3b07e667a9b`.

### Native process suites

After explicitly building each variant, execute its suites against that existing
runner (the `-x quarkusBuild` avoids redundant native regeneration):

```shell
./gradlew quarkusIntTest -x quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
./gradlew -Pverification=true quarkusIntTest -x quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
```

Use the Podman/Testcontainers environment exports above. Both commands passed.
The XML reports explicitly show the runner executable invocation, confirming
native execution rather than JVM fallback. The first unsuppressed test command
triggered redundant native regeneration and hit a 180-second tool timeout; the
already-completed runners and native artifact metadata were checked intact
before using `-x quarkusBuild`. No source change was substituted into those
prebuilt binaries.

- Production: `PackagedFoundationIT` (2 passing checks) and `PackagedRestartIT`
  (1 passing check). Verification-only tests are intentionally selected only for
  the explicit verification variant, not treated as production coverage.
- Verification: both foundation checks, restart, and all 4
  `PackagedVerificationIT` checks passed, with no skips in that variant.
- Reports: `build/test-results/quarkusIntTest/` and
  `build/verification/test-results/quarkusIntTest/`; native application logs are
  under the corresponding build directory. Secondary-process logs are temporary
  `company-secondary-*.log` files in the isolated temporary directory.

These executions establish six reactive mapping round-trips, enums/JSONB/Instant,
aggregate/completion/outbox rollback, shared responses/validation/strict JSON,
headers and framework errors, concurrent context, URL-based providers,
Flyway installation/repeated validation with intact history, runtime without
DDL, embedded OpenAPI without source access, and independent-process UUIDv7.
OCI image execution is tracked separately below.

### Completed native coverage

The expanded verification suite additionally passed division absence, malformed
JSON, wrong-country responses, 503 and timeouts. The second independent native
process deliberately uses port 9 for providers and passed actual refused
connections for Party/country/division, returning neutral mapped 503 responses.
This verifies network unavailability rather than only provider-returned 503.
The production binary's fixture exclusion and operational checks remain separate
from the verification binary's auxiliary APIs.

## Native OCI image (11.8)

Executed successfully:

```shell
podman build -f src/main/docker/Dockerfile.native -t company-registry-service:native .
```

- Image ID: `40b1d7498b91ffde4147472c6f83662639a8573b8265a73ab5892e2d5348146c`.
- Runtime base: `registry.access.redhat.com/ubi9/ubi-minimal:9.7`.
- User: `1001`; PID 1 command: `./application -Dquarkus.http.host=0.0.0.0`.
- No JVM on PATH, no source/config volume mounts, and `/work/application` has
  exactly the production runner SHA-256 recorded above.

The image ran on an isolated Podman network with PostgreSQL 18, using the local
bootstrap for separate `company_registry_runtime` and
`company_registry_migration` roles. `COMPANY_OUTBOX_MODE=disabled`; both provider
URLs pointed to unavailable container-loopback port 9; no broker was provisioned.
Readiness returned UP without business headers; JSON OpenAPI matched the service
title with no business paths; success/persistence/UUID verification routes all
returned the shared 404 envelope with valid context. PostgreSQL contained seven
tables including Flyway history, V1 checksum `-272976494`, and migration ownership
of application tables. This was a real image startup against a fresh database,
not a JVM fallback or a container with mounted source resources.

Disposable `company-registry-native-check-app`, `company-registry-native-check-db`
and the associated test network were removed after verification. The built image
and separate native binaries remain deliverables; the temporary mapped port was
not retained as a deployment.

Reproducible isolated database/image recipe (the passwords are disposable local
test values; no application tables are created by the bootstrap):

```shell
podman network create company-registry-native-check
podman run -d --name company-registry-native-check-db --network company-registry-native-check -e POSTGRES_DB=company_registry_native -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=local_native_admin -e DB_PASSWORD=native_runtime -e FLYWAY_DB_PASSWORD=native_migration docker.io/library/postgres:18-alpine
podman exec company-registry-native-check-db pg_isready -U postgres -d company_registry_native
podman exec -i company-registry-native-check-db psql -U postgres -d company_registry_native < docs/database/bootstrap-local.sql
podman run -d --name company-registry-native-check-app --network company-registry-native-check -p 127.0.0.1::8080 -e DB_USERNAME=company_registry_runtime -e DB_PASSWORD=native_runtime -e DB_REACTIVE_URL=postgresql://company-registry-native-check-db:5432/company_registry_native -e DB_JDBC_URL=jdbc:postgresql://company-registry-native-check-db:5432/company_registry_native -e FLYWAY_DB_USERNAME=company_registry_migration -e FLYWAY_DB_PASSWORD=native_migration -e COMPANY_OUTBOX_MODE=disabled -e PARTY_REGISTRY_BASE_URL=http://127.0.0.1:9 -e GEOGRAPHIC_REFERENCE_BASE_URL=http://127.0.0.1:9 company-registry-service:native
podman port company-registry-native-check-app 8080/tcp
podman top company-registry-native-check-app user pid args
podman rm -f company-registry-native-check-app company-registry-native-check-db
podman network rm company-registry-native-check
```

Wait for pg_isready before provisioning and for readiness before sending HTTP.
The randomly assigned host HTTP port is printed by `podman port`; the internal
application port is 8080 and PostgreSQL is 5432 on the isolated network. The host
binary's README example instead uses the local PostgreSQL mapping at 15433.

## Final JVM rebuild and evidence preservation

After native/image verification, `./gradlew clean build` passed in 2m 13s and
`./gradlew quarkusIntTest` passed in 1m 7s. This clean build intentionally replaces
the root Quarkus artifact metadata with a JVM package. The previously verified
native binaries were preserved before clean and restored afterward, with their
SHA-256 values checked unchanged; they were not silently rebuilt from another
source revision or tested as JVM artifacts.

The native runners, native artifact metadata and native XML reports are preserved
at `/tmp/opencode/company-registry-native-evidence.tar`, also extracted under
`/tmp/opencode/company-registry-native-verified/`. Its `build/` and
`build/verification/` trees distinguish the two native runs. Root
`build/test-results/test/` and `build/test-results/quarkusIntTest/` now hold the
final JVM runs. Preserve/export the native archive with a review if the temporary
directory will be cleaned. The native image tag remains
`localhost/company-registry-service:native` with the recorded image ID.

## Combined integration result (12.1)

The final `clean build` executed **120 JVM tests in 37 suites**, all passing with
zero skipped/failed/error cases. The required separate JVM `quarkusIntTest`
passed afterward. Both native builds, both native process variants, repeated
native startup and the real Podman image run above passed before this clean JVM
rebuild; their identities and report snapshots remain preserved separately.
No event-loop-blocking failure was observed in the reactive persistence, async
context or provider timeout scenarios. Expected deliberately triggered database
constraint and sanitized-500 log entries are not public response leaks or failed
tests. No required native scenario was substituted with a JVM result.

## HU01 productive native update (2026-10-17)

The T00 identities/results above remain historical foundation evidence. HU01's
new productive binary and image have their own
[acceptance record](hu01-acceptance-review.md): Mandrel JDK 25, 5 GiB/two threads,
5m 23s build, actual productive native routes, 201 paged identifiers, typed dates,
JSONB, original replay after restart/key rotation/provider failure, rollback,
server timeout and non-root image execution in all three storage modes.

The productive native process command used the same documented flags and
`quarkusIntTest -x quarkusBuild`; no required HU01 case was substituted with a
JVM or auxiliary fixture result. Native binary, metadata and XML/logs are
preserved separately under `/tmp/company-hu01-evidence/native/`. The image tag is
`company-registry-service:hu01-native`. Run
`python3 docs/operations/verify-hu01-native-image.py` from the repository root to
repeat the isolated image checks after building that tag. It creates its own
PostgreSQL/provider/keys and removes its test containers afterward.
## HU04 productive native verification

The HU04 executable uses the same documented Java 25 Mandrel builder, 5 GiB
compiler heap and two-thread configuration. From the repository root:

```sh
DOCKER_HOST=unix:///run/user/1000/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
QUARKUS_HTTP_TEST_PORT=0 DOCKER_HOST=unix:///run/user/1000/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew -I docs/operations/hu04-test-results.gradle -Phu04EvidenceRun=native quarkusIntTest -x quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
podman build -f src/main/docker/Dockerfile.native-micro -t company-registry-service:hu04-native .
python3 docs/operations/verify-hu04-native-image.py
```

`PackagedSiteIT` exercises the actual productive JVM/native process with an
external PostgreSQL 18 database, then restarts it against that preserved database
with K2 and all outbox modes. It checks Site JSONB replay after mutation, closure
and archival, local detail/page, cursor/HMAC, booleans/version/date values, public
errors and produced event validation against Draft 2020-12. It also checks absent
production verification routes and zero provider requests. The OCI harness uses
UID 1001, refused provider/broker URLs and owns only its disposable containers.

See [HU04 acceptance](hu04-acceptance-review.md) for executed results, artifact
identities and any pending checks. These commands alone are not execution evidence.

### HU04 deadline and pagination re-verification

After resolving verification warnings W1/W2, the final native runner was rebuilt
with the same documented 5 GiB/two-thread settings. Final evidence lives in
`build/hu04-evidence/fix-final-native/` and `fix-final-oci/`; the full JVM regression
is in `fix-final-jvm/`. Use `-Phu04EvidenceRun=fix-final-native` with the isolated
results init script and `quarkusIntTest -x quarkusBuild` to select the new report
location. Rebuild the image with `Dockerfile.native-micro`, then run:

```sh
EVIDENCE_DIR=build/hu04-evidence/fix-final-oci python3 -u docs/operations/verify-hu04-native-image.py
```

The image harness now configures a 2 s Site-registration deadline and observes a
real COMMIT waiting on an external PostgreSQL advisory-lock fixture. Disabled,
stored-only and published each returned 503 in about 2.00 s, then same-key retry
and replay retained one Site/completion and the mode-appropriate zero/one event.
The fixture is removed outside the productive image. UID 1001, original
restart/rotation/archival replay and no-provider checks also passed. The productive
native process suites executed seven checks with no failures/errors; four
verification-fixture checks remain intentionally excluded from that artifact.
The accepted runner/image hashes and exact per-mode times are recorded in
[hu04-acceptance-review.md](hu04-acceptance-review.md).

## HU05 verification completed (2026-10-18)

The full JVM gate passed 313 tests (94 suites, zero skipped/failures/errors),
productive build and verifyProductionIsolation. The final HU05 selection passed
20 tests, including eight concurrency cases. Both packaged JVM and native runs
reported eleven discovered tests: **seven executed successfully and four
verification-only fixtures intentionally skipped** in the productive artifact.
The productive Site suite exercises all HU05 operations/no-op, true/false historical
operativity, actual updated/closed schemas, retained PostgreSQL, restart, K1→K2 and
all outbox modes. It runs outside the repository working directory with providers
unavailable; no native fallback to a JVM artifact is used.

Executed commands (database-backed Gradle runs use the Podman/Testcontainers
exports shown earlier):

```shell
./gradlew -I docs/operations/hu05-test-results.gradle -Phu05EvidenceRun=final-jvm test build verifyProductionIsolation
./gradlew -I docs/operations/hu05-test-results.gradle -Phu05EvidenceRun=packaged-jvm test --tests '*SiteMutationDeadlineTest' --tests '*SiteMutationConcurrencyHttpTest' --tests '*SiteMutationBoundaryTest' --tests '*SiteMutationBudgetTest' quarkusIntTest
./gradlew quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
QUARKUS_HTTP_TEST_PORT=0 ./gradlew -I docs/operations/hu05-test-results.gradle -Phu05EvidenceRun=native-process quarkusIntTest -x quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
podman build -f src/main/docker/Dockerfile.native-micro -t company-registry-service:hu05-native .
EVIDENCE_DIR=build/hu05-evidence/oci python3 -u docs/operations/verify-hu05-native-image.py
```

- Mandrel 25.0.4.1-Final, Java 25.0.4.1+1-LTS; ELF x86-64 executable.
- Native Gradle build: BUILD SUCCESSFUL in 9m38s; image generation 8m49s,
  peak RSS 4.68 GB, configured 5 GB/two threads.
- Native process suite: BUILD SUCCESSFUL in 57s, seven productive tests executed,
  four fixture-only tests skipped, zero failures/errors.
- Runner SHA-256: `0f7f14d328daac0e090e3635a85af23e6f9389380a67525e6111a113fcd90f91`.
- Image ID: `80701551de9ab86ddaea47437befeb74b65931bb945b4ba3e9a4a63484a9f754`.
- Image configured/runtime UID: `1001`; all disabled/stored-only/published runs passed.

OCI prepares V4 with the existing JVM artifact's Flyway/JDBC libraries and original
migration sources, then starts the native image with startup migration disabled to
create an authentic HU04 acceptance. A subsequent normal native startup applies V5
from V4, preserving V1–V4 checksums and that acceptance. This external preparation is
necessary because Quarkus 3.40.1 does not expose a Flyway target runtime property.
An initial unsupported-target harness attempt failed its V4 assertion and is not
counted as passing evidence; the corrected harness completed successfully.

The OCI HU05 flows execute PATCH, no-op, activate/deactivate/close, historical true
operativity, closure/archival and K1→K2 replay across mode changes. Stored-only and
published preserve four mutation events (updated revisions 1/2/3, closed revision 4);
disabled preserves none. No-op/replay preserve Site revision. The retained HU04
commit-deadline regression observed an actual waiting COMMIT, returned 503 at
2.002–2.004 seconds, and recovered the same key with one Site/completion and the
mode-appropriate event count. HU05-specific uncertainty/cancellation fault injection
is established by the JVM suites; this HU04 image fault is not relabeled as HU05.
Providers/broker used refused loopback port 9, confirmed by the harness probe.

Reports/logs: `build/hu05-evidence/{final-jvm,packaged-jvm,native-process}/results/`,
`native-build.log`, `native-process.log`, `native-image-build.log`,
`native-image-run.log` and `oci/report.json`. `oci/prepare-v4.log` and
`oci/disabled-original.log` record actual V4 preparation and native V4→V5 migration.
The image harness removes only its owned disposable containers. No HU05 native gate
remains pending; compatible future-writer limits remain in the acceptance review.

## HU05 no-op clock correction verified (2026-10-18)

The HU05 runner/image identities in the preceding section describe the initial
implementation. Following formal verification, the no-op result codec was corrected
to allow an acceptance clock earlier than Site's unchanged update audit. Effective
changes retain strict acceptance time/actor checks; no snapshot format or migration
changed.

The productive runner was rebuilt with the same Mandrel container/runtime flags,
5 GB heap, two threads and UuidV7Generator runtime initialization shown above.
`build/hu05-evidence/noop-clock-native-build.log` records BUILD SUCCESSFUL in
10m6s and peak compiler RSS 4.74 GB. The subsequent `quarkusIntTest -x quarkusBuild`
run used `hu05EvidenceRun=noop-clock-native`, `QUARKUS_HTTP_TEST_PORT=0` and the same
native flags. It passed in 55s: eight productive tests executed, four fixture-only
tests skipped and zero failures/errors. XML reports live under
`build/hu05-evidence/noop-clock-native/results/quarkusIntTest/`.

PackagedSiteIT now includes an actual HTTP PATCH no-op and replay after a fixture
sets Site.updatedAt five minutes ahead of the application clock. Both responses
preserve the original Site, while completion time precedes its update audit and no
mutation event is stored. This test passed against the JAR and the new ELF binary.
The corresponding JVM selection passed 19 tests, build and production isolation;
the JAR process suite executed eight tests with four fixture-only exclusions.

The image was rebuilt using Dockerfile.native-micro. Running
`EVIDENCE_DIR=build/hu05-evidence/noop-clock-oci python3 -u docs/operations/verify-hu05-native-image.py`
passed all three modes under actual/configured UID 1001, V4→V5 migration and preserved
history, HU05 lifecycle/no-op replay, K1→K2 rotation and restart. The retained HU04
commit-deadline fixture also passed and is not relabeled as HU05 fault injection.
Only owned disposable containers were removed.

- Current runner SHA-256: `7a9d75e10373c7d0d912cb0b872d98c8ed8b3667f21376756004bdf249a090fd`.
- Current image ID: `5b9ed8661d5bf8649d68060010fe97b97bfb3cc683bba64b71b63691f05d728e`.
- Current OCI report: `build/hu05-evidence/noop-clock-oci/report.json`.
- Native process log: `build/hu05-evidence/noop-clock-native-process.log`.
- OCI logs: `build/hu05-evidence/noop-clock-image-build.log` and `build/hu05-evidence/noop-clock-image-run.log`.

The follow-up verification warning is resolved, with no required native check pending.
