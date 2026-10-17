# company-registry-service

## Foundation status and prerequisites

Java 25, Gradle wrapper 9.7.0, Quarkus 3.40.1, PostgreSQL 18 and Podman. Production and verification native binaries and the non-root UBI 9 image have passed their process checks; see [native evidence and commands](docs/operations/native-verification.md). HU01 implements Company registration, durable original-response replay and enriched current retrieval/listing; other business workflows remain in later HU. See [architecture decisions](docs/architecture/adr-001-reactive-native-foundation.md), [invariants](docs/architecture/adr-002-company-site-invariants.md), [normalization](docs/architecture/text-normalization.md) and [dependency credentials](docs/architecture/dependencies.md).

HU01 production startup also requires an external fingerprint keyring and active key ID.
See [key configuration](#hu01-fingerprint-keys) and the
[registration/retrieval contract](docs/contracts/company-registration-and-retrieval.md).
JVM/native HU01 acceptance is recorded separately from the earlier T00 evidence.

## Local database (Podman)

On a fresh local database, provision roles/grants separately from application migrations:

```shell
export DB_PASSWORD=company_registry_local
export FLYWAY_DB_PASSWORD=company_registry_local
podman run -d --name company-registry-db -p 127.0.0.1:15433:5432 \
  -e POSTGRES_DB=company_registry_service -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=company_registry_admin_local \
  -e DB_PASSWORD -e FLYWAY_DB_PASSWORD docker.io/library/postgres:18-alpine
podman exec company-registry-db pg_isready -U postgres -d company_registry_service
podman exec -i company-registry-db psql -U postgres -d company_registry_service < docs/database/bootstrap-local.sql
./gradlew quarkusDev
```

Wait for `pg_isready` to succeed before provisioning. Passwords shown are disposable local examples. The bootstrap runs once on a new database, creates no application tables, grants DDL to `company_registry_migration` and DML only to `company_registry_runtime`. Flyway creates the six application tables and its technical history. Hibernate schema generation is disabled, and V1 must never be edited to repair a deployed schema.

The Podman command above creates `company_registry_service` through `POSTGRES_DB`.
For an existing PostgreSQL server where this database is missing, run
[create-local-database.sql](docs/database/create-local-database.sql) once as a database
administrator connected to `postgres`, outside a transaction:

```shell
psql -U postgres -d postgres -v ON_ERROR_STOP=1 -f docs/database/create-local-database.sql
```

Then run [bootstrap-local.sql](docs/database/bootstrap-local.sql) connected to
`company_registry_service` to provision the roles and permissions.

Runtime configuration (required outside dev; dev defaults target the local database above):

| Variable | Meaning |
| --- | --- |
| `DB_USERNAME` | Runtime role, local `company_registry_runtime` |
| `DB_PASSWORD` | Runtime role password |
| `DB_REACTIVE_URL` | `postgresql://localhost:15433/company_registry_service` |
| `DB_JDBC_URL` | Migration transport URL, `jdbc:postgresql://localhost:15433/company_registry_service` |
| `FLYWAY_DB_USERNAME` | Migration role, local `company_registry_migration` |
| `FLYWAY_DB_PASSWORD` | Independent migration password |

Both URLs must target the same logical database. The JDBC URL is used for Flyway startup only; requests use reactive persistence. No broker or provider is needed for startup.

## External reference clients

| Variable | Default |
| --- | --- |
| `PARTY_REGISTRY_BASE_URL` | `http://localhost:8082` |
| `GEOGRAPHIC_REFERENCE_BASE_URL` | `http://localhost:8081` |
| `PARTY_REGISTRY_CONNECT_TIMEOUT_MS` / `GEOGRAPHIC_REFERENCE_CONNECT_TIMEOUT_MS` | `2000` |
| `PARTY_REGISTRY_READ_TIMEOUT_MS` / `GEOGRAPHIC_REFERENCE_READ_TIMEOUT_MS` | `5000` |

Timeouts must be positive finite milliseconds (at most int32 maximum). Override
URLs for the deployment; clients make no startup calls and perform no generic
retries. Party receives Tenant-Id/User-Id/Process-Id. Geography receives only
User-Id/Process-Id: Tenant-Id is never converted to company-id. Provider absence,
inactivity/type mismatch, timeout, unavailability and invalid contracts remain
distinct. See [contract provenance](docs/contracts/external-references.md).

## Outbox foundation

`COMPANY_OUTBOX_MODE` accepts `disabled` (default; no event storage), `stored-only`
and `published` (both select atomic storage). **T00 has no publisher in any mode**:
HU08 adds scheduling, claims/leases, retry/backoff, acknowledgements and sends.
There is no RabbitMQ connector/channel/emitter or required broker credential.

| Property suffix under `company-registry.outbox.publisher` | Variable | Default |
| --- | --- | --- |
| `poll-interval` | `COMPANY_OUTBOX_POLL_INTERVAL` | `5s` |
| `batch-size` | `COMPANY_OUTBOX_BATCH_SIZE` | `25` |
| `publish-timeout` | `COMPANY_OUTBOX_PUBLISH_TIMEOUT` | `5s` |
| `claim-lease` | `COMPANY_OUTBOX_CLAIM_LEASE` | `30s` |
| `initial-backoff` | `COMPANY_OUTBOX_INITIAL_BACKOFF` | `1s` |
| `maximum-backoff` | `COMPANY_OUTBOX_MAXIMUM_BACKOFF` | `5m` |
| `maximum-attempts` | `COMPANY_OUTBOX_MAXIMUM_ATTEMPTS` | `10` |
| `exchange` | `COMPANY_OUTBOX_EXCHANGE` | `company.events` |
| `routing-key` | `COMPANY_OUTBOX_ROUTING_KEY` | `company.events` |

Durations/counts must be positive, maximum backoff must not be below initial
backoff, and destinations must not be blank. Settings are validated at startup
even though T00 performs no publication. Local, test and production deployments
can override the listed variables without provisioning a broker.

## Tests

With the rootless Podman API socket active:

```shell
systemctl --user start podman.socket
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew test
```

Tests own/stop isolated PostgreSQL 18 containers and execute the same local bootstrap script, validate Flyway and verify runtime permissions. Pure Domain tests need no database. JDBC/psql provisioning is exclusively a test boundary. `./gradlew clean build` and `./gradlew quarkusIntTest` provide JVM checks; native execution is verified separately.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./gradlew quarkusDev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## JVM packaging for development

The application can be packaged using:

```shell script
./gradlew build
```

It produces the `quarkus-run.jar` file in the `build/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `build/quarkus-app/lib/` directory.

The application is now runnable using `java -jar build/quarkus-app/quarkus-run.jar`.

## Native delivery and verification

Build the production binary with Mandrel JDK 25 and explicit Podman:

```shell
./gradlew quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
```

Run against the bootstrapped local database (passwords are disposable examples):

```shell
export DB_USERNAME=company_registry_runtime
export DB_PASSWORD=company_registry_local
export DB_REACTIVE_URL=postgresql://localhost:15433/company_registry_service
export DB_JDBC_URL=jdbc:postgresql://localhost:15433/company_registry_service
export FLYWAY_DB_USERNAME=company_registry_migration
export FLYWAY_DB_PASSWORD=company_registry_local
export COMPANY_OUTBOX_MODE=disabled
./build/company-registry-service-1.0.0-SNAPSHOT-runner -Dquarkus.http.port=8080
```

The application exposes `/q/health/ready` and `/q/openapi` without business headers.
It requires neither a proxy nor reachable Party/Geographic/RabbitMQ for startup.
The production artifact exposes HU01 registration/retrieval and HU02 local PATCH.
Verification fixture routes belong to the separate verification artifact.

`-Pverification=true` explicitly builds the separate verification artifact under
`build/verification/`, compiling `src/verification/java`; it is not a runtime flag
that hides fixture routes in production. `integrationTest` shares only
`src/contractTest` and excludes unit-test CDI outputs. The native-test commands,
profile/socket configuration, hashes, builder digest, build resources and every
native suite are in [native-verification.md](docs/operations/native-verification.md).
Run those tests immediately after the corresponding native build; after a JVM
build the Quarkus artifact metadata describes that JVM package instead.

The verified heap was 5 GiB with two compiler threads; 3 GiB exhausted the compiler
Java heap. Both successful builds took about ten minutes on the recorded machine.

Build the production image:

```shell
podman build -f src/main/docker/Dockerfile.native -t company-registry-service:native .
```

Use the documented database variables and a reachable Podman database hostname
when running the image. The [isolated image recipe](docs/operations/native-verification.md#native-oci-image-118)
records the verified run, ports and cleanup. The image uses UBI 9 Minimal, user
1001 and the native binary directly, without a JVM or source mounts.

## Fingerprint keys (HU01/HU02)

HU01 requires `COMPANY_IDEMPOTENCY_ACTIVE_KEY_ID` and an external keyring under
`company-registry.idempotency.keys.<id>`. Supply Base64 secrets representing at
least 32 random bytes through a protected external configuration file (for
example `QUARKUS_CONFIG_LOCATIONS=file:/run/secrets/company-keys.properties`).
There are no production secret defaults. Never commit the file or log its values.

```properties
# External secret file; replace the placeholder with actual Base64 secret material.
company-registry.idempotency.keys.K1=<Base64 of at least 32 random bytes>
```

IDs use 1–64 ASCII letters, digits, underscore or hyphen. The active ID must exist
in the keyring. All instances must associate each ID with exactly the same secret.
To rotate, distribute K1 and K2 to every instance first, then select K2 as active.
New acceptances use K2; historical retries use the policy and key ID on their
completion record. Retain K1 and its policy for every dependent completion.
There is no TTL, automatic deletion or re-signing of accepted records. A missing
historical key or policy is a sanitized server failure, never a content conflict
or authorization to create again. Each request allocates its own MAC state.
The test profile has explicitly disposable keys; they are not production defaults.

HU01 JVM, packaged/native-process and non-root image evidence, with the
AC01–AC13 mapping and reproducible image check, is recorded in
[HU01 acceptance](docs/operations/hu01-acceptance-review.md).

HU02 uses the same external keyring with its own `company.update.v1` fingerprint
policy and operational snapshot codec, both version 1. Retain every historical
key needed by either operation, including no-op acceptances; there is no TTL.
Updates compare using the stored policy/key ID after rollback as well as on the
initial replay lookup. Their intent includes Company ID, original expected
version and patch presence/null/value; actor/process and current Company state
are excluded. HU01 canonical bytes and enriched Company/Party snapshots retain
their original contract. HU02 snapshots retain operational values and explicit
internal nulls independently of HTTP omission and never require Party lookup.

HU02 adds local `PATCH /v1/companies/{companyId}` with mandatory decimal If-Match
and optional Idempotency-Key. See [the update contract](docs/contracts/company-information-management.md)
for clearing, no-op, durable replay, error precedence and audit semantics.
`COMPANY_UPDATE_WAIT_BUDGET` sets a shared local database budget (default `10s`,
positive and finite). PATCH requires no provider or broker connectivity.
