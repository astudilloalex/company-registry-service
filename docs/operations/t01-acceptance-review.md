# T01 local operational validation

Validated on 2026-10-23 (America/Guayaquil). This is local JVM/native/OCI evidence;
production deployment and the existing off-VPS backup system remain separate gates.
No OpenSpec artifacts were created or updated by this implementation.

## Deployment contract

- Company listens on container port 8083 and publishes only `127.0.0.1:8083:8083`.
- All modes join `company-db`, `internal-services`, and `messaging`.
- Initial production mode is disabled. No RabbitMQ credentials/connectivity are required.
- The working Party Registry pipeline at `fd603dd08fd980b96b5f35056345d638e4080bb2`
  supplies the native/GHCR/SSH/rootless Quadlet model, atomic installation and recovery.
- Historical keys are external, immutable by ID and additive across rollback.
- Flyway migrations V1–V9 and business/event contracts remain unchanged.

## Executed checks

| Check | Result | Local evidence |
| --- | --- | --- |
| JVM unit, architecture, PostgreSQL/RabbitMQ and contract suite | 582 passed; no failures/errors/skips | `build/t01-jvm-validation.log`, `build/t01-operations/jvm/` |
| JVM packaged production process suite | 27 passed; 4 verification-variant-only cases intentionally skipped | `build/t01-jvm-process.log`, `build/t01-operations/jvm-process/` |
| Native build, Mandrel Java 25, compatible architecture, 5 GiB compiler heap, two compiler threads | Passed in 5m06s | `build/t01-native-build.log` |
| Native production process suite | 27 passed; the same 4 variant-only cases skipped | `build/t01-native-process.log`, `build/t01-operations/native-process/` |
| Deployment renderer, recovery and effective inspection tests | 21 passed | `build/t01-deployment-tests.log` |
| Actual rootless Quadlet generator, dry run with temporary files | Accepted container and network units | `build/t01-quadlet-generation.log` |
| Native OCI operational acceptance | Passed | `build/t01-container-acceptance.log`, `build/t01-operations/acceptance.json` |
| Workflow YAML, every embedded shell block, deployment shell syntax and changed-file whitespace | Passed | Validation commands below |

The productive executable SHA-256 is
`cb5211004dbba93ef6c36e4ad77aa8aa4a6fdd6aac968b938738cfec9735effc`.
The local OCI image is `localhost/company-registry-service:t01`, image ID
`efbe0d943da327694d8ca2a98cd85905ff2ca2342b7d220b4556983da4baf40a`.

The OCI test uses temporary namespaced networks and a PostgreSQL 18 container,
a synthetic Party provider and disposable protection material. It verifies:

- Actual non-root/read-only startup, capability restrictions, protected keyring
  access through the keep-id mount and curl health execution inside port 8083.
- Exactly three effective networks and a loopback-only host mapping to port 8083.
- Company/Site creation, identical-key replay and retrieval in disabled mode with
  zero new outbox events and no broker present.
- Local Site reads remain available with Party stopped; enriched Company reads
  return controlled 503. HTTP client/server and reactive pool metrics are present
  without tenant/Party identifiers as labels.
- 320 successful Site reads with 16 concurrent workers and collected Podman stats.
- PostgreSQL loss leaves liveness available and readiness returns 503; readiness
  recovers after the same isolated database restarts.
- Both Company roles are denied connection to an unrelated logical database by
  the scoped HBA rules. Runtime cannot write Flyway history after finalization.
- Stored-only creates pending events without a RabbitMQ broker.
- A real custom-format `pg_dump` and `pg_restore` preserve two Companies, two Sites,
  four completions, two pending outbox events, nine Flyway entries and protection
  keys. Identical-key replay still succeeds after restore with Party stopped.
- Every owned temporary container, network and database volume is removed afterwards.

Quarkus 3.40's built-in reactive datasource check has a 20-second connection wait.
The negative HTTP diagnostic test allows 30 seconds to observe its 503; the
three-second Podman probe considers a stalled check failed, matching the existing
Party health/restart model. Test harness corrections for bootstrap readiness and
provider pagination preserve application validation and the final successful run.

## Resource observations

Ten Podman samples during the bounded read workload reported at most 63.82 MB
container memory, 15 tasks and 5.18% reported CPU. CPU samples are not an instantaneous
peak measurement. The fixture ran successfully with 256 MiB, one CPU and 128 tasks.
This provides local evidence for an initial bounded resource profile with headroom;
it is not a production capacity estimate or a write-heavy/soak benchmark.
The deployment template now fixes the initial profile at 256M memory, 50% of one
CPU and 128 tasks, without requiring GitHub resource variables. The 50% CPU limit
was selected after these observations and has not been measured by this run.
Recheck startup and representative load with that limit on the shared VPS.
Raw samples and metrics are in `build/t01-operations/{container-stats.json,metrics.prom}`.

## Reproduction

Use the existing Podman Testcontainers socket configuration from the README:

```sh
python3 -m unittest discover -s deploy/tests -v
bash -n deploy/vps/deploy.sh deploy/vps/backup.sh
./gradlew test build verifyProductionIsolation
./gradlew quarkusIntTest
./gradlew quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.march=compatibility -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
./gradlew quarkusIntTest -x quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false
podman build -f src/main/docker/Dockerfile.native -t localhost/company-registry-service:t01 .
python3 -u deploy/tests/container_acceptance.py localhost/company-registry-service:t01
```

Run artifact builds/process suites in sequence. Production excludes the fixture
routes; a deliberately separate verification artifact owns its four conditional cases.

## Remaining production acceptance

Before the issue can be closed, provision Company database/network/HBA changes
on the existing VPS, configure this repository's protected production deployment
inputs, and execute its pipeline. Reuse Party's SSH identity and verified host
configuration; encrypted secret values cannot be recovered through GitHub's API.
Verify actual shared network/DNS membership, restart/reboot recovery, business smoke,
negative database access and absence of a public port/Nginx route there.

Finally integrate and recover a backup through the existing encrypted off-VPS
destination and exercise a schema/event-compatible application rollback on that
environment. Local restore, shell failure simulations and Quadlet generation do
not establish those production outcomes. The operational procedures and required
secret/variable names are in `deploy/README.md`.
