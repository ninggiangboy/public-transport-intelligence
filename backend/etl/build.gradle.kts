plugins {
    id("pti.spring-boot-app")
    id("pti.contract-test")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":analytics"))
}

description = "ETL: Spring Kafka stream consumers (profile stream) and Spring Batch jobs (profile batch)."
