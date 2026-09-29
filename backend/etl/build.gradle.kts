plugins {
    id("pti.spring-boot-app")
    id("pti.contract-test")
}

description = "ETL: Spring Kafka stream consumers (profile stream) and Spring Batch jobs (profile batch)."

pti {
    unitLineCoverage = 0.70
    coverageExcludes.add("dev/pti/etl/config/**") // DOC-44 §7
}

dependencies {
    implementation(project(":common"))
    implementation(project(":analytics"))
    implementation(platform(libs.spring.cloud.aws.dependencies))

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.datasource.micrometer)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.boot.starter.batch.jdbc)
    implementation(libs.spring.cloud.aws.starter.s3)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.jdbc.template)
    implementation(libs.resilience4j.spring.boot4)
    implementation(libs.resilience4j.micrometer)
    implementation(libs.uuid.creator)
    implementation(libs.caffeine)
    implementation(libs.json.schema.validator)
    runtimeOnly(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.batch.test)
    testImplementation(libs.hibernate.validator)
    testImplementation(libs.micrometer.tracing.bridge.otel)

    testFixturesImplementation(testFixtures(project(":common")))
    testFixturesImplementation(project(":common"))
    testFixturesImplementation(libs.jackson.databind)
    testFixturesImplementation(libs.spring.boot.starter.validation)

    integrationTestImplementation(project(":db"))
    integrationTestImplementation(testFixtures(project(":db")))
    integrationTestImplementation(testFixtures(project(":common")))
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.spring.batch.test)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.micrometer.registry.prometheus)

    contractTestImplementation(project(":db"))
    contractTestImplementation(testFixtures(project(":common")))
    contractTestImplementation(libs.hibernate.validator)
    contractTestImplementation(testFixtures(project(":db")))
    contractTestImplementation(libs.postgresql)
    contractTestImplementation(libs.testcontainers.kafka)
}

// `./gradlew :etl:contractTest -PcontractWriteExpected=true` writes missing expectations of the contract examples.
tasks.named<Test>("contractTest") {
    systemProperty("pti.contract.write-expected", providers.gradleProperty("contractWriteExpected").getOrElse("false"))
}
