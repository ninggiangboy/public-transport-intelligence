plugins {
    id("pti.spring-boot-app")
    id("pti.contract-test")
}

dependencies {
    implementation(project(":common"))
}

description = "Source simulator: GTFS-realtime producer, ticketing seeder and ledger."
