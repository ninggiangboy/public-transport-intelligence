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
    implementation(libs.uuid.creator)
    runtimeOnly(libs.micrometer.registry.prometheus)

    testFixturesImplementation(testFixtures(project(":common")))
}
