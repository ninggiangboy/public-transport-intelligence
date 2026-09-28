plugins {
    id("pti.spring-boot-app")
    id("pti.contract-test")
}

dependencies {
    implementation(project(":common"))
}

description = "REST and SSE API, the only entry point for the frontend."
