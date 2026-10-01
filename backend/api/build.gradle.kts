plugins {
    id("pti.spring-boot-app")
    id("pti.contract-test")
}

description = "REST and SSE API, the only entry point for the frontend."

pti {
    unitLineCoverage = 0.60
    coverageExcludes.add("dev/pti/api/**/config/**") // DOC-44 §7
}

dependencies {
    implementation(project(":common"))

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.datasource.micrometer)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.boot.starter.security.oauth2.resource.server)
    implementation(libs.springdoc.openapi.webmvc.api)
    implementation(libs.bucket4j.core)
    implementation(libs.caffeine)
    runtimeOnly(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.hibernate.validator)
    testImplementation(libs.awaitility)
    testImplementation(testFixtures(project(":common")))
    testImplementation(libs.micrometer.tracing.bridge.otel)

    // JwtFixture mints RS256 tokens with Nimbus, which the resource server brings.
    testFixturesImplementation(libs.spring.boot.starter.security.oauth2.resource.server)
    testFixturesImplementation(project(":common"))

    integrationTestImplementation(project(":db"))
    integrationTestImplementation(testFixtures(project(":db")))
    integrationTestImplementation(testFixtures(project(":common")))
    integrationTestImplementation(testFixtures(project()))
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.spring.boot.starter.webmvc.test)
    integrationTestImplementation(libs.spring.boot.starter.security.test)
    integrationTestImplementation(libs.micrometer.registry.prometheus)
}
