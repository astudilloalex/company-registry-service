# ADR 001 — Reactive foundation and native delivery

Status: accepted by the `establish-company-registry-foundation` design.

## Context and decision

Company Registry expects concurrent, network/database-bound private API work. Select the Quarkus reactive profile end to end: Java 25, Gradle Kotlin DSL, the existing Gradle 9.7.0 wrapper, and Quarkus plugin/BOM 3.40.1. Domain computation remains synchronous. Mutiny is permitted in Application orchestration and I/O port signatures, never in Domain.

Operational persistence uses Hibernate Reactive/Panache and the reactive PostgreSQL client against PostgreSQL 18. External HTTP uses Quarkus REST Client Jackson. No blocking call, manual subscription, await, JDBC or ORM `EntityManager` belongs on an event-loop request path. Flyway/JDBC is an isolated startup migration boundary; it does not authorize operational JDBC access.

Persistence and external I/O ports live in Application. **Narrow override:** T00's issue suggests repository interfaces in Domain; the approved change design instead locates asynchronous ports in Application to keep Domain framework-independent. This does not authorize HTTP, SQL, Quarkus or ORM in Application.

Outer composition/adapters wrap complete application operations with programmatic `Panache.withSession` / `Panache.withTransaction`. Do not adopt preview reactive `@Transactional` or mix transaction styles. Aggregate mutation, accepted idempotency completion and conditional outbox insertion commit or roll back together in one local transaction. Never concurrently use the same reactive session. Provider HTTP validation occurs outside long-held database locks; there is no distributed atomicity guarantee.

## Native baseline

Native delivery is required, not an optional replacement for JVM development. Use `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25`, explicit Podman container builds, and UBI 9 Minimal runtime with user 1001. Record the resolved builder digest, Java/native-image versions, platform and actual runtime checks during native verification. Do not claim those checks from configuration alone.

Compare [Party build](https://github.com/astudilloalex/party-registry-service/blob/fd603dd08fd980b96b5f35056345d638e4080bb2/build.gradle.kts) and [Party configuration](https://github.com/astudilloalex/party-registry-service/blob/fd603dd08fd980b96b5f35056345d638e4080bb2/src/main/resources/application.properties): same Java/Quarkus baseline, Mandrel JDK 25, runtime initialization of a concrete UUID generator, separate packaged/native test sources, no `src/test` CDI fixtures in packaged verification. Company must register its own generator if needed, never Party's FQN.

Production excludes verification sources at compile time. Separate native verification binaries may expose auxiliary operations for T00 tests, with differentiated output/evidence. Test Flyway, all six persistence mappings, JSONB/enums/Instant, envelopes/errors, enabled clients and disabled outbox in a real native process. Production itself must expose health/OpenAPI without context, embed migration/OpenAPI resources and contain no auxiliary routes. A passing JVM build does not establish native acceptance.

## Consequences

The explicit Mutiny coupling makes asynchronous application contracts consistent but ties them to this execution profile. Domain remains independently testable. Programmatic transactions preserve the inner-layer boundary. Build and integration complexity is accepted because delivery and verification must exercise the real native artifact. No pass-through use cases or complete HU workflows are required by T00.

## Verification so far

`./gradlew --version`: Gradle 9.7.0, OpenJDK 25.0.4.1, Linux amd64. `./gradlew compileJava` and the initial JUnit suite pass. Native build/process verification belongs to section 11 and remains pending.
