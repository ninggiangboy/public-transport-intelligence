plugins {
    id("pti.spring-boot-app")
    id("pti.contract-test")
}

dependencies {
    implementation(project(":common"))
}

description = "Asynchronous AI triage of DLQ records and anomalies."
