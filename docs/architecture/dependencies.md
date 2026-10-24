# Dependency baseline

Telephone validation uses `com.googlecode.libphonenumber:libphonenumber:9.0.41` (Maven Central) as a pure numbering-plan computation library, not an I/O/framework adapter. Domain's architecture rule explicitly allows only this additional library package. Its numbering metadata must be included and exercised during native verification; JVM policy tests alone do not establish native support.

- Java toolchain and compiler release: 25.
- Gradle wrapper: 9.7.0 (existing distribution and checksum preserved).
- Quarkus plugin and enforced platform BOM: 3.40.1.
- Shared HTTP contract: `com.alexastudillo.libraries:api-response-quarkus-errors:1.0.0-SNAPSHOT`, selected through `apiResponseVersion` in `gradle.properties`.

The shared module is consumed as a published dependency, with its transitive contract and Quarkus error handling. No library classes are copied into this service. The SNAPSHOT is mutable and is not an immutable release.

Repository and credentials follow [Party Registry's build](https://github.com/astudilloalex/party-registry-service/blob/main/build.gradle.kts): Maven Central, Maven Local, and `https://maven.pkg.github.com/astudilloalex/java-shared-libraries`. Supply `GITHUB_ACTOR` and `GITHUB_TOKEN`, or external Gradle properties `gpr.user` and `gpr.key` (for example in the user's Gradle properties). Never commit credentials.

REST server/client Jackson, Hibernate Reactive Panache, reactive PostgreSQL, Validator, OpenAPI, context propagation, health, Messaging RabbitMQ, Scheduler and Micrometer/Prometheus versions come exclusively from the Quarkus BOM. HU08 resolves SmallRye RabbitMQ 4.37.0, Mutiny Vert.x RabbitMQ 3.23.1 and Vert.x RabbitMQ 4.5.34. Delivery is lazy and enabled only in published mode; disabled/stored-only do not connect to a broker. JDBC remains reserved for Flyway startup migrations; operational persistence and delivery use reactive APIs.
