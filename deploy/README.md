# Company Registry VPS deployment

This deployment adapts Party Registry's working pipeline at commit
`fd603dd08fd980b96b5f35056345d638e4080bb2`: Java 25/Mandrel native builds,
GHCR images tagged `sha-<commit>`, SSH/SCP delivery, rootless Podman Quadlet,
private environment rendering, atomic configuration replacement and recovery.
No OpenSpec workflow is involved.

Company listens on **8083 in every environment**, including inside its container.
The VPS publishes only `127.0.0.1:8083:8083`. There is no Nginx route.
The container always joins `internal-services`, `company-db`, and `messaging`,
including when `COMPANY_OUTBOX_MODE=disabled`, the initial production mode.
Party and Geographic retain their own internal port 8080.

## GitHub production configuration

The `production` environment belongs to this repository. Reuse the same VPS,
deployment user, SSH key and verified known-hosts configuration used by Party;
configure their values independently here. GitHub's API cannot read or copy
encrypted secret values from Party's repository.

Required production secrets:

| Secret | Purpose |
| --- | --- |
| `VPS_HOST`, `VPS_USER` | Existing VPS deployment target |
| `VPS_SSH_PRIVATE_KEY`, `VPS_SSH_KNOWN_HOSTS` | Existing SSH credentials and verified host identity |
| `DB_PASSWORD`, `FLYWAY_DB_PASSWORD` | Separate Company runtime and migration role passwords |
| `COMPANY_IDEMPOTENCY_KEYS_JSON` | JSON object mapping every historical key ID to its original Base64 secret |

The keyring format is `{"K1":"<Base64 of at least 32 random bytes>"}`. Supply
real material only through protected secrets. Existing IDs must never be
relabelled or assigned a new secret. The renderer does not generate or rotate keys.
Keep its JSON valid and include older IDs when adding a new active key.

The non-secret resource limits are versioned directly in
`quadlet/company-registry-service.env.example`: `COMPANY_MEMORY_MAX=256M`,
`COMPANY_CPU_QUOTA=50%`, and `COMPANY_PIDS_LIMIT=128`. The workflow reads them
from that template; no GitHub variables are required for these limits. CPU quota
is a maximum relative to one CPU, not a reservation. Validate this initial profile
against representative VPS load; the previous local resource samples used 100% CPU.
The renderer still rejects missing or unbounded limits. The active key variable
`COMPANY_IDEMPOTENCY_ACTIVE_KEY_ID` defaults to the non-secret ID `K1`, while
the historical key material remains a required protected secret.

Business smoke tests are optional. Set both `SMOKE_TENANT_ID` and `SMOKE_PARTY_ID`
to a dedicated operational test tenant and one of its legal entities to enable
creation/replay/retrieval checks after deployment has committed. Missing fixtures
skip these tests; incomplete or invalid UUIDs produce a warning and skip them.
Fixture preparation/copy failures and smoke failures do not block deployment or
trigger rollback. Smoke execution is bounded to 90 seconds with a five-second
forced-termination grace period. Effective container settings, health, metrics,
and database privilege finalization remain required deployment gates.

The environment template lists all optional overrides: database URLs and role
names, internal provider URLs, pool size/timeouts and publisher settings.
`RABBITMQ_USERNAME` and `RABBITMQ_PASSWORD` are optional secrets for `disabled`
and `stored-only`; `published` requires them. Initial deployment remains disabled.
No public host, administrator role or embedded URL password is a production default.

## Provision the existing infrastructure

Run all container commands as the existing rootless deployment user. Verify
that its user manager has lingering enabled, GHCR authentication survives reboot,
and the current Podman supports the Quadlet options already used by Party plus
`UserNS=keep-id:uid=1001,gid=1001`.

1. Keep `internal-services.network` and `messaging.network` in the existing
   infrastructure configuration. Add `quadlet/company-db.network` there and
   start `company-db-network.service` through `systemctl --user`.
2. Add `Network=company-db.network` to the **infrastructure-owned** PostgreSQL
   Quadlet, preserving every existing network. Make the matching connection
   persistent. If a PostgreSQL restart is required, coordinate it with the other
   services; a one-off `podman network connect` alone is not persistent configuration.
3. As the existing PostgreSQL administrator, load the two protected password
   environment variables and run `database/provision.sql`, initially connected
   to `postgres`. Copy the whole `database/` directory so `\ir` resolves.
   This creates only Company roles/database and schema permissions. Flyway
   remains the exclusive authority for application tables and migrations V1–V9.
4. Merge `database/company-pg_hba.conf.example` **before** permissive existing
   rules in PostgreSQL's managed HBA configuration and reload it. Those rules
   constrain only the two Company roles. Database ACLs alone cannot deny them
   access to other databases whose `PUBLIC` role still has `CONNECT`.
5. Start Company in disabled mode to run Flyway using the separate migration
   identity. The deployment verifier then connects through `POSTGRES_CONTAINER`
   (default `postgresql`) with that migration identity, grants DML on the six
   application tables and revokes runtime access to `flyway_schema_history`
   before committing deployment success. For manual provisioning/restoration,
   execute the equivalent `database/grant-runtime.sql` connected to the Company
   database. Review the allowlist when future migrations add application tables.
6. Verify runtime DML succeeds, runtime DDL/history writes fail, and **both**
   Company roles fail to connect to Party, Geographic and Keycloak databases.
   Do not revoke shared services' existing database grants.

Provisioning can be repeated without rotating existing passwords or changing
another service's database. Unsafe existing role attributes/memberships are rejected.
Credential rotation is a separate coordinated database/broker operation.

## Deploy and verify

Pull requests run the quality/build pipeline. `main` pushes and manual runs from
`main` additionally publish and deploy. Required gates cover changed-file
whitespace, deployment tests/shell syntax, architecture and unit tests, real
PostgreSQL/RabbitMQ integration and contracts, JVM process tests, native build
and native process tests. A failed gate prevents deployment.

The workflow renders a private env file and separate properties keyring. It
copies them and the reviewed deployment scripts over verified SSH. The script
serializes deployments, validates files without shell-sourcing secrets, checks
all three networks, and pulls the versioned image before touching live configuration.

Installed destinations:

| File | Destination |
| --- | --- |
| Quadlet | `~/.config/containers/systemd/company-registry-service.container` |
| Environment, mode 0600 | `/home/containers/deployment/env/company-registry-service.env` |
| Keyring, mode 0600 | `/home/containers/deployment/secrets/company-registry-service.properties` |

The read-only keyring mount and `keep-id` mapping make it readable by container
user `1001:1001` without group/world permissions or copying secrets into the image.
`DEPLOYMENT_ENVIRONMENT_FILE` and `DEPLOYMENT_KEYRING_FILE` may override destinations.

Readiness gates systemd startup (`Notify=healthy`) and deployment success, as in
Party. Repeated readiness failure kills the process; systemd attempts recovery
with a bounded start rate. Therefore a sustained PostgreSQL outage can restart
the container. Liveness `/q/health/live` remains independent of external systems.
Quarkus 3.40's built-in reactive database readiness check can wait up to 20 seconds
for a connection. The three-second Podman probe treats that wait as a failed
probe; a diagnostic client needs a longer timeout to receive the eventual 503.
After exhausting the configured restart rate, recover with
`systemctl --user reset-failed company-registry-service.service` and restart it.
Startup `/q/health/started` and readiness `/q/health/ready` need no business headers.
Party, Geographic, and RabbitMQ do not participate in API readiness.

After startup, `verify.py` checks effective network membership, loopback-only
port mapping, user/security restrictions, three health endpoints and actual metrics.
To run it again on the VPS:

```sh
python3 verify.py --mode disabled
journalctl --user -u company-registry-service.service --since '10 minutes ago'
podman healthcheck run company-registry-service
```

For business smoke tests, prepare a private JSON fixture containing `tenantId`
and an existing non-archived LEGAL_ENTITY `partyId` belonging to a dedicated
test tenant. Run `verify.py --mode disabled --smoke-fixtures /private/smoke.json`.
It creates/replays one stable Company and Site, then reads both. These records
and their idempotency keys persist intentionally; never substitute a business
Party already associated with another Company. Geographic-dependent mutations
need valid geographic fixtures and are covered by packaged integration tests.
This manual command reports a nonzero status on failure; automated deployments
report optional smoke failures as warnings after the required checks have passed.

## Metrics and logging

Private `/q/metrics` supplies request count/duration/outcomes, provider HTTP
latency/errors, reactive PostgreSQL pool metrics (`postgresql_*`) and existing
`company_outbox_*` delivery/backlog metrics. Reuse the existing internal collector;
no public monitoring port or new monitoring stack is provisioned. Normalize HTTP
route labels; tenant, user, process and event IDs are diagnostic context, never
metric labels. Verify transport failures as well as HTTP error responses.

Logs follow Party's timestamp/level/category/thread and
`[pid=…] [userId=…] [tenantId=…]` format. HTTP completion records contain method,
status and duration without bodies, query values or credentials. Request context
is cleaned on success, failure and cancellation. Outbox diagnostics keep the
existing allowlisted outcomes and original event correlation.

Backlog availability must be interpreted separately from zero backlog: unknown
pending/age values are NaN with availability 0. Alert on unavailable measurements,
increasing oldest age, retries, terminal failures and uncertain transitions.

## Enable RabbitMQ publication later

Keep all three networks attached. Provision the existing `platform` vhost with
a durable topic `company.events` exchange and a dedicated Company publisher.
The connector declares that exchange, so allow configure/write only for
`^company\.events$`, with no queue/read permission. Prepare and verify durable
consumer bindings before publication; broker confirms alone do not prove routing
or consumer processing. The Company routing template is `%s` (event type), not
Party's fixed routing key.

Change mode to `stored-only` to capture pending events without connectivity.
After topology and credentials are verified, deploy in `published` and monitor.
Use `verify.py --mode published`. Return to `stored-only` to pause publication.
Disabled mode captures no new events and cannot backfill them later. Existing
pending, published, failed, and idempotency records are never deleted by deployment.

## Backup and restore

Integrate `vps/backup.sh` with the **existing encrypted off-VPS backup job**.
Supply `PGPASSWORD` through its protected environment and optionally `PGUSER`
for an approved backup identity (default migration owner). The script invokes
PostgreSQL 18's `pg_dump` inside the existing container, producing one custom-format
snapshot of the entire logical database, verifies its archive listing, copies
the historical keyring and writes SHA-256 checksums into a new private directory.
Passwords are inherited by `podman exec --env PGPASSWORD`, not placed in arguments.

The database snapshot contains Companies, Sites, addresses, contacts,
idempotency records, outbox states and Flyway history in one consistent snapshot.
Keys are copied afterwards because historical keyrings are additive. Include the
protected deployment configuration and PostgreSQL role/access recovery material
in the existing secret backup policy as well. A local directory is only staging;
configure and test off-VPS upload, retention, encryption and recovery access in
the existing backup system. This repository cannot establish its remote destination.

Test restoration into an isolated PostgreSQL 18 instance first:

1. Recover the snapshot, keyring, configuration and administrative access from
   the off-VPS destination; validate `SHA256SUMS`.
2. Provision the dedicated roles/database with `database/provision.sql`.
3. Restore with PostgreSQL 18 `pg_restore --exit-on-error --no-owner --no-acl
   --role=company_registry_migration -d company_registry_service <snapshot>`
   using an administrative connection. Reapply `database/grant-runtime.sql`
   and the Company HBA restrictions. Do not run `clean`, `repair`, or hand-edit history.
4. Start a schema/event-compatible application in `stored-only` using the
   recovered full keyring. Compare all six table counts, event IDs/statuses,
   completion snapshots and Flyway checksums with the backup baseline. Test
   identical-key HTTP replay with Party unavailable.
5. Re-enable publication only after verifying broker topology. Pending events
   resume after reservation expiry. A broker can already contain events newer
   than the restored database; publication is at least once. Preserve `eventId`
   so consumers can deduplicate. Never reset PUBLISHED/FAILED rows indiscriminately.

## Rollback and local acceptance

Deployment failure restores the previous env and Quadlet, restarts that version,
and verifies readiness. If recovery fails, private recovery files are retained
and the command fails. A failed first deployment removes its env/Quadlet.
The installed keyring **is retained**, even after failure: requests accepted during
the attempted rollout may already require a newly added key. Old IDs cannot be
removed or overwritten during preflight; configuration rollback never discards keys.

For a manual compatible-image rollback, reuse the installed env/keyring and run:

```sh
bash deploy.sh ghcr.io/astudilloalex/company-registry-service:sha-<compatible-commit> /private/deployment-scripts
```

Application rollback does not revert Flyway, rotate database passwords, downgrade
event contracts, or rewrite committed records. Use a version compatible with the
current schema and historical result codecs. Database restoration requires a
separate recovery decision and the procedure above.

Local deterministic verification:

```sh
python3 -m unittest discover -s deploy/tests -v
bash -n deploy/vps/deploy.sh deploy/vps/backup.sh
```

After a production native build, build `localhost/company-registry-service:t01`
using `src/main/docker/Dockerfile.native`, then execute
`python3 deploy/tests/container_acceptance.py`. This creates only isolated,
run-labelled local networks/containers and removes them afterwards. It exercises
actual non-root read-only startup, keyring access, all three networks, disabled
and stored-only modes without a broker, failure isolation, database/HBA privileges,
concurrent reads, `pg_dump`/`pg_restore`, historical replay and resource samples.
Evidence lives under ignored `build/t01-operations/`. Local evidence does not
establish successful production deployment or an off-VPS restore.
