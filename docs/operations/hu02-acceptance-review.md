# HU02 acceptance evidence

Change: `add-company-information-management` (spec-driven).
Story: [HU02: Company Information Management](https://github.com/astudilloalex/company-registry-service/issues/4).

All required JVM, productive-JAR, native-process and non-root image checks below have completed successfully.

## Acceptance traceability

| Criterion | Implementation and executable coverage |
| --- | --- |
| AC01 successful update | CompanyInformationPolicy; UpdateCompany; CompanyUpdateMappingTest; CompanyUpdateHttpTest; PackagedUpdateIT |
| AC02 omitted/null semantics | CompanyInformationPatchTest; CompanyInformationPolicyTest; strict HTTP bodies and packaged clearing/no-op |
| AC03 concurrent modifications | Independent HTTP connections with observable PostgreSQL lock barriers; one 200 and one 412; one completion/event |
| AC04 required version | ExpectedVersionParserTest; strictBodyAndHeadersRejectBeforeWriting; missing/repeated/overflow preconditions |
| AC05 immutable identity | Closed PATCH reader; explicit identity/audit/lifecycle/system-field rejection; preserved repository identity/audit/Site |
| AC06 canonical uppercase | Domain Unicode/uppercase expansion/code-point limits; immutable fingerprint byte vector; HTTP/process canonical output |
| AC07 duplicate code | Tenant-qualified SQL uniqueness; canonical/ARCHIVED collisions; independent Companies competing for code; own-code no-op |
| AC08 archived Company | Version evaluated before lifecycle; ARCHIVED rejects new patches including no-op; historical replay still succeeds |
| AC09 cross-tenant access | Qualified lock and UPDATE predicates; absent/foreign IDs both 404; no external lookup |
| AC10 no effective change | Equality/empty/redundant-null policy; physical xmin unchanged; protected stale no-op rejected; durable original no-op replay |
| AC11 audit metadata | One microsecond instant/exact actor; original creation audit retained; Company/completion/event coherence; replay retains original actor |
| AC12 atomic outbox | All three modes with/without key; real PostgreSQL PENDING/zero counters; rollback after each required write; native-process and OCI coverage |

## Additional contract and recovery cases

- V3 clean installation and upgrade from V2 retain six application tables, HU01 rows, V1/V2 sources and checksums. Incompatible historical update metadata stops migration without changing accepted records.
- Update fingerprints bind resource, original version and each field's presence/null/canonical value. Actor/process are excluded; URL bytes are exact. Historical policy/key selection survives competing active keys and restart.
- Strict operational snapshots retain internal nulls/types through actual JSONB and omit nulls in HTTP. Corrupt/unknown snapshot/policy/key yields sanitized 500 with no reapplication.
- Company, optional completion and enabled event roll back together after failures following each required write. Same key remains reusable after rollback. A reported failure after actual commit resolves a durable acceptance in a fresh session; without key it returns unavailable without automatic mutation retry.
- A shared positive finite database budget decreases through lookup, mutation, cleanup and recovery. Server lock/statement timeout causes rollback, no partial completion/event and allows a subsequent retry.
- PostgreSQL-trigger-injected SQLSTATE 40001/40P01 aborts exercise real driver classification, transaction rollback and key reuse. These are controlled server aborts, not evidence of a scheduler-created deadlock cycle.
- `company.updated.v1` uses eleven envelope fields and seven payload fields. Contract tests validate Draft 2020-12, all four lifecycle states, forbidden properties/types and producer cross-field invariants. Production HU02 emits editable states only.
- HTTP optional outer whitespace is transport syntax under [RFC 9112 §5.1](https://www.rfc-editor.org/rfc/rfc9112.html#section-5.1). The approved spec clarification validates the extracted decimal value. A raw socket test accepts `If-Match:  0 `; internal whitespace, quotes, signs, leading zeros, duplicates and overflow remain invalid.

## Reproduction

With the existing rootless Podman socket:

```bash
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew test quarkusBuild verifyProductionIsolation -Dquarkus.http.test-port=0
./gradlew quarkusIntTest -Dquarkus.http.test-port=0
```

The zero test port avoids conflicting with another local application. Production-process suites share external fixtures only, without unit-test CDI outputs. PackagedUpdateIT launches independent processes with providers refused, K2 active, and each outbox mode; original change and no-op snapshots survive later archival.

Build and test the productive native binary using the established Mandrel procedure:

```bash
./gradlew quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
./gradlew quarkusIntTest -x quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2' -Dquarkus.http.test-port=0
podman build -f src/main/docker/Dockerfile.native -t company-registry-service:hu02-native .
python3 docs/operations/verify-hu02-native-image.py
```

The OCI verifier owns disposable containers, checks user 1001, uses PostgreSQL 18 with refused provider URLs, covers every mode and restart/rotation/archival replay, and removes its containers on exit. Binary resource configuration includes `META-INF/contracts/events/**`; JVM isolation checks require the update schema and example. Event contracts are verified against produced native-process events.

## Observed outcomes

- Full JVM run: 200 tests in 61 suites, zero failures/errors/skips; production isolation passed. Build duration: 4m 26s.
- Productive JAR run: 10 discovered checks in five suites, six executed successfully and four auxiliary verification-fixture checks intentionally skipped because production excludes those routes/classes. Zero failures/errors. Duration: 2m 15s.
- Native build passed: Mandrel JDK 25, 5 GiB heap and two compiler threads; peak RSS 4.72 GB; 13m 48s total, with 12m 58s inside native-image. Runner is an ELF x86-64 executable of 108,440,632 bytes. Its embedded resource names include both update schema and example.
- Productive native-process run passed in 33s: the same five suites/10 discovered checks, six executed successfully and four auxiliary verification-fixture checks intentionally skipped. No failures/errors. Produced native events passed the Draft 2020-12 update schema and result/audit assertions.
- Non-root OCI image passed all three modes using runtime UID 1001. Disabled retained one Company/two completions/zero events; stored-only and published retained one Company/two completions/two PENDING events with zero delivery counters. Each mode verified optional-key updates, durable no-op, replay after restart/K2 rotation/archival and no new effects during replay. A socket preflight observed connection refusal (errno 111) at the configured provider/broker endpoint 127.0.0.1:9; no source tree or JVM was mounted in the image.

Native runner SHA-256: `d3928137a3b605b7c0d0258e8595c110cefe7630e7b74fdcb4dd383773c688a5`.
Image: `localhost/company-registry-service:hu02-native`, ID `7025bf7fa701a95f4803d07f64e6f8a412dfae650b7ae6f3b4032d6e580c17b0`.
Builder image ID: `f046cb1da286f9afce3b5ebb7d8260f265ee1b7ce22a02d1d868f722d4f14f20`.
Full logs are `jvm-build.log`, `jvm-process.log`, `native-build.log`, `native-process.log`, `oci-build.log` and `oci-verification.log`. Separate `jvm/` and `native/` folders preserve XML reports, summaries and artifact hashes. `oci/report.json`, per-mode process/log files and stored event snapshots record image execution.
Evidence directory: `build/hu02-evidence/` (logs, XML reports, hashes and OCI report).

## Delivery boundaries

HU02 does not publish RabbitMQ in any mode and does not implement HU03 lifecycle workflows. The ARCHIVED event schema is a contract handoff for HU03; stored events and routing metadata prepare HU08 delivery. No claim of publication, delivery ordering or exactly-once behavior is made. No deployment, archive of this OpenSpec change or remote repository mutation is part of this verification.
