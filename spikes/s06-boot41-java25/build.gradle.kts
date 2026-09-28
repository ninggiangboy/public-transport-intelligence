// Spike S-06: verify that the libraries chosen in DOC-11 work together on
// Spring Boot 4.1 and Java 25. Throwaway code; not part of the product build.
plugins {
    java
    jacoco
    checkstyle
    id("com.github.spotbugs") version "6.5.12"
    id("org.owasp.dependencycheck") version "13.0.0"
    id("org.springframework.boot") version "4.1.1"
    id("com.google.cloud.tools.jib") version "3.5.4"
    id("com.diffplug.spotless") version "8.10.3"
}

group = "dev.pti.spike"
version = "0.0.1"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation(platform("io.awspring.cloud:spring-cloud-aws-dependencies:4.1.1"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))

    implementation("org.springframework.boot:spring-boot-starter-batch-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    implementation("net.javacrumbs.shedlock:shedlock-spring:7.10.1")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-s3")
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
    implementation("io.github.resilience4j:resilience4j-micrometer:2.4.0")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.1.1")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.20.0")
    implementation("com.networknt:json-schema-validator:3.0.7")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")
    implementation("com.github.f4b6a3:uuid-creator:6.1.1")
    implementation("com.github.f4b6a3:ulid-creator:5.2.4")
    implementation("net.ttddyy.observation:datasource-micrometer-spring-boot:2.3.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-batch-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers-toxiproxy")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("spike.postgres.image", providers.gradleProperty("pgImage").getOrElse("postgres:17.11"))
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

jib {
    from {
        image = "eclipse-temurin:25-jre"
        // jibDockerBuild loads one platform into the local daemon; CI pushes both with `jib` (DOC-41 §5).
        platforms {
            providers.gradleProperty("jibPlatforms").getOrElse("linux/arm64").split(",").forEach { p ->
                platform { architecture = p.substringAfter("/"); os = p.substringBefore("/") }
            }
        }
    }
    to { image = "pti-spike-s06:local" }
    container { mainClass = "dev.pti.spike.SpikeApplication" }
}

spotless {
    java { palantirJavaFormat() }
}

jacoco { toolVersion = "0.8.15" }
tasks.jacocoTestReport { dependsOn(tasks.test); reports { xml.required = true } }

checkstyle {
    toolVersion = "14.3.0"
    config = resources.text.fromString(
        """<?xml version="1.0"?>
        <!DOCTYPE module PUBLIC "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN" "https://checkstyle.org/dtds/configuration_1_3.dtd">
        <module name="Checker"><module name="TreeWalker"><module name="UnusedImports"/><module name="AvoidStarImport"/></module></module>""")
}

spotbugs {
    toolVersion = "4.10.4"
    ignoreFailures = true
}
tasks.spotbugsMain { reports.create("xml") { required = true } }
