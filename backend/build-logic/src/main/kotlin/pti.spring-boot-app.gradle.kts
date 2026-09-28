// Conventions for Spring Boot applications (etl, api, triage-worker, source-simulator).
plugins {
    id("pti.java-conventions")
    id("pti.jib-conventions")
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(libs.findLibrary("spring-boot-starter").get())
    testImplementation(libs.findLibrary("spring-boot-starter-test").get())
    testImplementation(libs.findLibrary("archunit-junit5").get())
    testImplementation(testFixtures(project(":common")))
    "integrationTestImplementation"(libs.findLibrary("spring-boot-testcontainers").get())
}

jib {
    container {
        ports = listOf("8080", "9080")
        // zstd-jni (Kafka compression) loads a native library.
        jvmFlags = listOf("--enable-native-access=ALL-UNNAMED")
    }
}
