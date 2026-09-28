plugins {
    id("pti.spring-boot-app")
    id("pti.contract-test")
}

description = "Source simulator: GTFS-realtime producer, ticketing seeder and ledger."

dependencies {
    implementation(project(":common"))
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.uuid.creator)
    runtimeOnly(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.postgresql)

    testFixturesImplementation(testFixtures(project(":common")))

    integrationTestImplementation(project(":db"))
    integrationTestImplementation(testFixtures(project(":db")))
    integrationTestImplementation(libs.postgresql)
}
