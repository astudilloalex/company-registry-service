# HU01 acceptance evidence

Change: `add-company-registration-and-retrieval` (spec-driven).
All required JVM, productive native-process and non-root image checks below have executed successfully.

## JVM regression and real processes

`./gradlew clean build` passed with **159 tests in 50 suites**, zero failures,
errors or skips. `./gradlew quarkusIntTest` then passed against a separate
productive JVM process, PostgreSQL 18 and controlled HTTP providers. Five
productive-process checks passed; four auxiliary verification checks were
intentionally skipped because their classes/routes are absent from production.

After strengthening the shared provider fixture to 201 identifiers and validating
the actual stored event, a final `./gradlew build quarkusIntTest` passed again in
2m 3s with the same complete test counts.

The suites include clean V2 installation, real V1 upgrade with historical data,
preflight failure for unknown HU01 provenance, V1 checksum -272976494, all six
tables/ownership references and existing snapshot preservation. Mandatory write
failures at Company/completion/event each roll back everything and permit retry.
The four race cases use independent HTTP requests and observable PostgreSQL
lock barriers, then verify both outcomes and exact Company/completion/event
counts. An unknown outbox duplicate is a sanitized 500, never a business 409.

Recovery tests inject an error after a real commit and recover its durable
completion, roll back a transaction holding uniqueness, and race two instances
using active K1/K2 while verifying with the winning historical key. Server
statement timeout is observed while blocked, followed by rollback/session close,
503 and successful retry. Party pagination validates ownership, scalar types,
states, duplicates/cursors, deadlines, cancellation and bounded concurrency.

The productive-process suite creates Company with nested Party/identifiers and
LocalDate/Instant, reads/list it, changes Company and provider fixtures, replays
the original response, and launches another isolated process with K2 active,
providers unavailable and a changed outbox mode. It verifies original enriched
201 content, current HTTP context, no new writes/events and different-input 409.

JVM XML reports and logs were preserved under
`/tmp/company-hu01-evidence/jvm/`. The working build report directories are
`build/test-results/test/` and `build/test-results/quarkusIntTest/`.

## Acceptance traceability

| HU01 criterion | Executed coverage |
| --- | --- |
| AC01 DRAFT/0 registration | RegistrationPolicyTest; CompanyRegistrationHttpTest; PackagedCompanyIT |
| AC02 tenant-scoped Party identity | PartyReadAdapterTest; reference and Company HTTP tests |
| AC03 NATURAL_PERSON rejection | RegistrationPolicyTest; CompanyRegistrationHttpTest |
| AC04 unique tenant/Party | PostgreSQL lock-barrier races and exact row counts |
| AC05 unique canonical code | RegistrationPolicyTest; PostgreSQL code-conflict race |
| AC06 canonical text | Policy, DTO, fingerprint and real HTTP/process assertions |
| AC07 durable replay | AcceptedResultCodecTest; commit-loss recovery; process restart/rotation/provider loss |
| AC08 changed effective input | Fingerprint vectors; completion race; HTTP and process 409 |
| AC09 unavailable dependency | HTTP stubs, refused connection, later-page timeout, no partial acceptance |
| AC10 tenant isolation | Mandatory context tests; SQL filters; foreign-ID 404; cursor tenant binding |
| AC11 state/version/audit reads | Current/replay HTTP and productive-process comparisons |
| AC12 bounded deterministic list | Tied timestamps and IDs, AND filters, cursor tests, enrichment limits, EXPLAIN |
| AC13 conditional atomic outbox | Three-mode SQL tests, mandatory-write rollback, races and productive-process row checks |

The 10,000-row EXPLAIN uses existing ix_companies_tenant_created, scans 57 entries
for 51 results, filters six and uses four shared buffers; observed execution was
0.102 ms. No additional index was introduced. The repeatable plan test writes
`build/reports/hu01/status-page-explain.txt`.

## Productive native executable

Mandrel 25.0.4.1-Final/JDK 25, Podman, a 5 GiB compiler heap and two threads built
the productive executable successfully in **5m 23s** (native generation 5m 4s).
Peak compiler RSS was 4.73 GB; final file size was 107.21 MB.

SHA-256: `cc59b125c5ed3c1a0e08826824d86495e51e7b8e8a60c8a87b2af6a1bb8759c4`.
The runner is a real ELF executable; process tests assert ELF magic before
restarting it. The native process suite passed all five productive checks, with
only the four intentionally absent auxiliary-fixture checks skipped. It covers
201 identifiers across a full 200-item page plus a final page, nested dates and
booleans, JSONB snapshots, native MAC/key rotation, current GET/list, stored-event
schema validation, rollback, server timeout and original replay after restart
with providers unavailable. No HU01 requirement relies on a verification-only
native endpoint, so an additional auxiliary native build is unnecessary here.
The earlier T00 auxiliary evidence remains in native-verification.md.

The binary, native artifact metadata, build log, process log and XML reports are
preserved under `/tmp/company-hu01-evidence/native/`. JVM reports are preserved
separately; a later JVM package does not replace this native evidence. The root
quarkus-artifact.properties currently identifies the final JVM package, while the
native runner remains unchanged. Rebuild native or restore its preserved metadata
before reusing a native process command with -x quarkusBuild.

## Non-root native image

Image: `localhost/company-registry-service:hu01-native`.
Image ID: `d696aeaa67e549e0a1145296915ef51cd189293ce3716c8444f7ca4d77c4be59`.
It contains the runner with the SHA-256 above, uses UBI 9 Minimal and runs as
user **1001**, verified from image configuration and the live container.
No source/config volumes are mounted. No broker is provisioned.

| Mode | HTTP creation | Company/completion/event rows | Event state |
| --- | --- | --- | --- |
| disabled | 201 | 1/1/0 | Omitted |
| stored-only | 201 | 1/1/1 | PENDING, attempts 0, delivery version 0 |
| published | 201 | 1/1/1 | PENDING, attempts 0, delivery version 0 |

Each mode passed health, creation, enriched GET/list, embedded OpenAPI with the
three actual operations, and 404 for verification-only routes. The database,
external test keyring and HTTP provider belong to this isolated check. All
created app/database containers were removed afterward; the built image remains.
Reproduce from the repository root after building/tagging the image:
`python3 docs/operations/verify-hu01-native-image.py`.
Reports and per-mode application logs are in `/tmp/company-hu01-evidence/`.

## Review boundaries

HU01 produces/stores events only. HU08 still owns publication, retries/claims,
RabbitMQ bindings and the final routing-key default alignment. Production startup
requires externally managed fingerprint keys; no production secret is supplied.
The change is ready for OpenSpec verification/review before explicit archival.
