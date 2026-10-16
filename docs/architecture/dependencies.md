# Dependency baseline

- Java toolchain and compiler release: 25.
- Gradle wrapper: 9.7.0 (existing distribution and checksum preserved).
- Quarkus plugin and enforced platform BOM: 3.40.1.
- Shared HTTP contract: `com.alexastudillo.libraries:api-response-quarkus-errors:1.0.0-SNAPSHOT`, selected through `apiResponseVersion` in `gradle.properties`.

The shared module is consumed as a published dependency, with its transitive contract and Quarkus error handling. No library classes are copied into this service. The SNAPSHOT is mutable and is not an immutable release.

Repository and credentials follow [Party Registry's build](https://github.com/astudilloalex/party-registry-service/blob/main/build.gradle.kts): Maven Central, Maven Local, and `https://maven.pkg.github.com/astudilloalex/java-shared-libraries`. Supply `GITHUB_ACTOR` and `GITHUB_TOKEN`, or external Gradle properties `gpr.user` and `gpr.key` (for example in the user's Gradle properties). Never commit credentials.

REST server/client Jackson, Hibernate Reactive Panache, reactive PostgreSQL, Validator, OpenAPI, context propagation, health, and core Messaging versions come exclusively from the Quarkus BOM. Core Messaging does not install a RabbitMQ connector or configure a publisher. JDBC is reserved for the later Flyway migration boundary; operational persistence uses reactive APIs.
