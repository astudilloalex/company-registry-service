import java.util.jar.JarFile

plugins {
    java
    id("io.quarkus")
}

val verificationBuild = providers.gradleProperty("verification").orElse("false").get().toBooleanStrict()
if (verificationBuild) {
    layout.buildDirectory.set(layout.projectDirectory.dir("build/verification"))
    sourceSets.main { java.srcDir("src/verification/java") }
}

repositories {
    mavenCentral()
    mavenLocal()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/astudilloalex/java-shared-libraries")
        credentials {
            username = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as String?
            password = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.key") as String?
        }
    }
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project
val apiResponseVersion = providers.gradleProperty("apiResponseVersion").get()

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-rest-client-jackson")
    implementation("io.quarkus:quarkus-hibernate-reactive-panache")
    implementation("io.quarkus:quarkus-reactive-pg-client")
    implementation("io.quarkus:quarkus-hibernate-validator")
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("io.quarkus:quarkus-smallrye-context-propagation")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-messaging")
    // Blocking access is reserved exclusively for Flyway startup migrations.
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.alexastudillo.libraries:api-response-quarkus-errors:$apiResponseVersion")
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.41")
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.quarkus:quarkus-junit-mockito")
    testImplementation("io.quarkus:quarkus-test-vertx")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    testImplementation("io.swagger.parser.v3:swagger-parser:2.1.47")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.awaitility:awaitility")
    testImplementation("com.networknt:json-schema-validator:2.0.1")
}

group = "com.alexastudillo"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

// Reusable HTTP fixtures are test-only; packaged/native verification opts in separately.
sourceSets.test {
    if (!verificationBuild) java.srcDir("src/verification/java")
    java.srcDir("src/contractTest/java")
}

sourceSets.named("integrationTest") {
    java.setSrcDirs(listOf("src/integrationTest/java", "src/contractTest/java"))
    resources.setSrcDirs(listOf("src/integrationTest/resources"))
    compileClasspath -= sourceSets.test.get().output
    runtimeClasspath -= sourceSets.test.get().output
}

quarkus {
    sourceSets { setExtraNativeTest(project.sourceSets["integrationTest"]) }
}

tasks.named<Test>("quarkusIntTest") {
    systemProperty("quarkus.test.integration-test-profile", "test")
    systemProperty("verification.artifact", verificationBuild.toString())
    systemProperty("native.artifact", providers.systemProperty("quarkus.native.enabled").orElse("false").get())
    classpath = sourceSets["integrationTest"].runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.named<Test>("testNative") {
    systemProperty("quarkus.test.integration-test-profile", "test")
    systemProperty("verification.artifact", verificationBuild.toString())
    systemProperty("native.artifact", "true")
    classpath -= sourceSets.test.get().output
}

tasks.processResources {
    from("docs/contracts/events") { into("META-INF/contracts/events") }
    from("docs/contracts/company-registry.openapi.yaml") {
        into("META-INF")
        rename { "openapi.yaml" }
    }
}

tasks.register("verifyProductionIsolation") {
    group = "verification"
    description = "Checks that the packaged production application contains its contract and no verification fixtures."
    dependsOn("quarkusBuild")
    onlyIf { !verificationBuild }
    doLast {
        val jars = fileTree(layout.buildDirectory.dir("quarkus-app/app")) { include("*.jar") }.files
        check(jars.isNotEmpty()) { "Packaged application JAR is missing" }
        var contractFound = false
        var eventContractFound = false
        var updateContractFound = false
        var updateExampleFound = false
        var siteContractFound = false
        var siteExampleFound = false
        val siteMutationContracts = mutableSetOf<String>()
        val lifecycleContracts = mutableSetOf<String>()
        jars.forEach { jar ->
            JarFile(jar).use { archive ->
                archive.entries().asSequence().forEach { entry ->
                    check(!entry.name.startsWith("com/alexastudillo/companyregistry/api/verification/")) {
                        "Verification fixture included in production: ${entry.name}"
                    }
                    if (entry.name == "META-INF/openapi.yaml") contractFound = true
                    if (entry.name in setOf(
                            "META-INF/contracts/events/company.activated.v1.schema.json",
                            "META-INF/contracts/events/company.activated.v1.example.json",
                            "META-INF/contracts/events/company.deactivated.v1.schema.json",
                            "META-INF/contracts/events/company.deactivated.v1.example.json",
                            "META-INF/contracts/events/company.updated.v1.archived.example.json")) lifecycleContracts.add(entry.name)
                    if (entry.name == "META-INF/contracts/events/company.created.v1.schema.json") eventContractFound = true
                    if (entry.name == "META-INF/contracts/events/company.updated.v1.schema.json") updateContractFound = true
                    if (entry.name == "META-INF/contracts/events/company.updated.v1.example.json") updateExampleFound = true
                    if (entry.name == "META-INF/contracts/events/company.site.created.v1.schema.json") siteContractFound = true
                    if (entry.name == "META-INF/contracts/events/company.site.created.v1.example.json") siteExampleFound = true
                    if (entry.name in setOf(
                            "META-INF/contracts/events/company.site.updated.v1.schema.json",
                            "META-INF/contracts/events/company.site.updated.v1.example.json",
                            "META-INF/contracts/events/company.site.closed.v1.schema.json",
                            "META-INF/contracts/events/company.site.closed.v1.example.json")) siteMutationContracts.add(entry.name)
                }
            }
        }
        check(contractFound) { "Packaged contract is missing" }
        check(eventContractFound) { "Packaged creation event contract is missing" }
        check(updateContractFound && updateExampleFound) { "Packaged update event contract/example is missing" }
        check(siteContractFound && siteExampleFound) { "Packaged Site event contract/example is missing" }
        check(siteMutationContracts.size == 4) { "Packaged Site mutation event contracts/examples are missing" }
        check(lifecycleContracts.size == 5) { "Packaged Company lifecycle event contracts/examples are missing" }
    }
}

tasks.withType<JavaCompile> {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "1g"
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
