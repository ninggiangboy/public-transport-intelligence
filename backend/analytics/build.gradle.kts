plugins {
    id("pti.java-conventions")
}

description = "Detectors and scheduled analytics shared by etl (ADR-0014)."

// DOC-23 §1.1: common, spring-jdbc, Caffeine and uuid-creator; no Spring Kafka, no Spring Batch. spring-boot is for
// @ConfigurationProperties in the config packages and micrometer-core for the metric adapters; neither reaches
// domain or application (A-12).
dependencies {
    api(project(":common"))
    implementation(libs.spring.jdbc)
    implementation(libs.spring.boot)
    implementation(libs.micrometer.core)
    implementation(libs.caffeine)
    implementation(libs.uuid.creator)
    implementation(libs.slf4j.api)

    testFixturesApi(project(":common"))
    testFixturesImplementation(libs.spring.boot)
    testFixturesImplementation(libs.hibernate.validator)

    testImplementation(libs.hibernate.validator)
    testImplementation(libs.spring.boot)

    integrationTestImplementation(project(":db"))
    integrationTestImplementation(testFixtures(project(":db")))
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.spring.jdbc)
    integrationTestImplementation(libs.micrometer.core)
}
