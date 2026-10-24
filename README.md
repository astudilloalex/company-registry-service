# company-registry-service

## Company lifecycle (HU03)

POST `/v1/companies/{companyId}/{activate,deactivate,archive}` takes business
context headers, decimal Company `If-Match`, optional opaque `Idempotency-Key`
and either no body or `{}`. It returns the complete operational Company with 200.
Activation requires an ACTIVE LEGAL_ENTITY Party and current ACTIVE HEADQUARTERS.
Deactivation and archival are local; Site status, revision, audit and children stay
independent. New redundant commands return 409. ARCHIVED is terminal.

Replay scopes are `(tenant, operation, key)` with independent
`company.activate.v1`, `company.deactivate.v1`, `company.archive.v1` namespaces.
The original If-Match belongs to the intent; actor/process and observations do not.
Absent body and `{}` are equivalent. Accepted results, format/policy versions and
historical signing keys have no TTL or automatic deletion. During K1→K2 rotation,
configure both original secrets on every instance, then change only the active ID
to K2. Old results are verified with stored K1; missing material fails with a
sanitized 500. Never overwrite a key ID's secret. Replays preserve their original
result after another cycle or archival, with the current response Process-Id.

`company-registry.lifecycle.wait-budget` / `COMPANY_LIFECYCLE_WAIT_BUDGET` defaults
to `10s`, must be positive and representable in nanoseconds. One monotonic budget
covers lookup, protected preparation, Party HTTP, final acceptance, commit, cleanup
and recovery. Party transport timeouts also apply. No lock/session crosses HTTP.
An unresolved result returns 503; recovery never repeats a transition and starts
only after confirmed transaction finalization. Later Party changes do not trigger
automatic Company changes or imply distributed atomicity.

Events are company.activated.v1, company.deactivated.v1 and company.updated.v1
(ARCHIVED). Disabled stores none; stored-only/published store the applicable fact
atomically with Company and its optional completion. Delivery remains HU08.

## Foundation status and prerequisites

Java 25, Gradle wrapper 9.7.0, Quarkus 3.40.1, PostgreSQL 18 and Podman. Production and verification native binaries and the non-root UBI 9 image have passed their process checks; see [native evidence and commands](docs/operations/native-verification.md). HU01 implements Company registration, durable original-response replay and enriched current retrieval/listing; HU02/HU03 and HU04/HU05 provide Company information/lifecycle and Site registration/retrieval/lifecycle; address/contact commands and publication remain later HU. See [architecture decisions](docs/architecture/adr-001-reactive-native-foundation.md), [invariants](docs/architecture/adr-002-company-site-invariants.md), [normalization](docs/architecture/text-normalization.md) and [dependency credentials](docs/architecture/dependencies.md).

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

## Transactional outbox

`COMPANY_OUTBOX_MODE` accepts `disabled` (default; no event storage), `stored-only`
and `published` (both select atomic storage). Only `published` reserves and sends
committed events asynchronously. Change configuration and restart the same
JVM/native artifact to switch modes. `disabled` and `stored-only` require no
broker credentials and do not connect to RabbitMQ.

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
| `routing-key` | `COMPANY_OUTBOX_ROUTING_KEY` | `%s` |

Durations must be positive and representable: polling/lease up to 1h, timeout
up to 5m, backoffs up to 24h. Batch is 1–500; attempts 1–100. Lease must exceed
timeout; maximum backoff must be at least initial backoff. Validation runs in
all modes. Destinations cannot contain controls and must fit 255 UTF-8 bytes.
Routing requires exactly one literal `%s`, replaced by eventType;
`integration.%s` is valid. Migrate the former fixed override `company.events`
to `%s`; fixed keys and other formats now fail startup.

Published requires external `RABBITMQ_HOST`, `RABBITMQ_USERNAME` and
`RABBITMQ_PASSWORD`; `RABBITMQ_PORT` defaults to 5672 (1–65535),
`RABBITMQ_VIRTUAL_HOST` to existing `platform`. Their properties are under
`company-registry.outbox.broker` (`host/port/username/password/virtual-host`).
Prepare the topic durable exchange and consumer queues/bindings before enabling
delivery. See [delivery operations](docs/contracts/outbox-publication.md) for
permissions, confirmation semantics, retry/recovery, metrics and rollback.

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

HU04 uses that keyring with its independent `company.site.create.v1` policy one.
Its fingerprint includes tenant, operation, route Company, canonical code/name
and effective Site type; omitting type equals explicit BRANCH. Keep all historical
Site policy versions, snapshot codecs and key IDs without TTL. To rotate, deploy
K1/K2 to all instances before selecting K2; existing acceptances still verify
with their stored K1. Missing historical material yields a sanitized server error,
never a new acceptance or a rewritten snapshot. Company fingerprints remain unchanged.

HU04 exposes POST/list under `/v1/companies/{companyId}/sites` and detail under
`/v1/companies/{companyId}/sites/{siteId}`. Creation requires Idempotency-Key and
no If-Match; reads need neither. Company → Site locking serializes all new Site
types against the local parent, leaving its version/audit untouched. Configure
`COMPANY_SITE_REGISTRATION_WAIT_BUDGET` for the shared database deadline (default
`10s`, positive finite), covering acquisition, commit, cleanup waits and recovery.
Atomic work reserves up to 100 ms (at most 10% of its remaining time) for rollback;
the response deadline also bounds finalization that does not complete.
Recovery after rollback checks the durable acceptance in
a fresh session; unresolved uncertainty returns 503 and permits the same-key retry.
Keep V4 and all accepted data/keys on application rollback, restoring a compatible
version or suspending these routes. HU03 Company and HU05 Site transitions are implemented through explicit commands; see their lifecycle contracts.

### HU05 Site mutation persistence

HU05 has four independent operation namespaces: `company.site.update.v1`,
`company.site.activate.v1`, `company.site.deactivate.v1` and `company.site.close.v1`.
Fingerprint policy 1 signs compact UTF-8 JSON in fixed order: fingerprintVersion,
tenantId, operation, companyId, siteId, decimal-string expectedVersion, request.
PATCH request contains code/name/siteType with explicit present/value; lifecycle
commands sign `{}` whether the HTTP body is absent or empty. Actor, process, key,
states and outbox mode are excluded. The original policy and key ID verify replay.

The independent HU05 snapshot format 1 stores Site, original Company status and
boolean operativity. The Company status is internal verification material, absent
from the twelve-field public Site representation. PATCH no-op can retain older Site
audit than its completion. Retain all completion records, formats, policies and
historical keys without TTL. Distribute K1/K2 before activating K2; never relabel K1
acceptances. Unknown historical material produces a sanitized server error.
Rollback preserves V5, Sites, completions, events and keys; deploy compatible code
or suspend HU05 routes rather than deleting accepted history or resetting versions.

`COMPANY_SITE_MUTATION_WAIT_BUDGET` configures
`company-registry.site-mutation.wait-budget` (default `10s`, positive and finite).
One budget covers lookup, locking, mutation, finalization, cleanup and recovery.
Unresolved results return 503; retry an identical keyed request to resolve its
original acceptance. Writes are never automatically reexecuted after uncertainty.


### HU06 address acceptance configuration

`COMPANY_SITE_ADDRESS_WAIT_BUDGET` defaults to `10s` and must be positive and
finite. Zero, negative or nanosecond-overflowing budgets abort startup before
any address request arrives. Address acceptance uses the existing externally
configured fingerprint keyring, with independent policy/format version 1 for the three
`company.site.address.{create,update,delete}.v1` namespaces. Retain historical
keys and completions without TTL across rotations, restarts and rollback.
See `docs/architecture/persistence-contract.md` for signed intent, snapshots,
cursors, coordination and retention. JVM verification does not establish native
execution; native outcomes are recorded separately in the operations evidence.

HU07 [Site contacts](docs/contracts/company-site-contact-management.md) implement
POST/list/detail/PATCH/DELETE under each Site with shared If-Match, explicit
primaries, canonical uniqueness, logical deletion and durable replay. EMAIL V1
is ASCII and fully lowercase, replacing the earlier local-case preservation
contract. `COMPANY_SITE_CONTACT_WAIT_BUDGET` defaults to `10s` and is validated
at startup. See the published OpenAPI for examples and descriptive errors.
