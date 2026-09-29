plugins {
    id("pti.java-conventions")
}

description = "Shared message contracts, business keys, canonical JSON and GTFS time utilities."

pti {
    unitLineCoverage = 0.90
}

dependencies {
    api(libs.jackson.databind)
    api(libs.jakarta.validation.api)
    api(libs.jspecify)
    implementation(libs.json.schema.validator)
    implementation(libs.json.canonicalization)

    testImplementation(libs.hibernate.validator)
    // ErrorClassifier recognises these libraries' exceptions by name; the tests throw the real ones (DOC-30 §6).
    testImplementation(platform(libs.spring.cloud.aws.dependencies))
    testImplementation(libs.postgresql)
    testImplementation(libs.spring.jdbc)
    testImplementation(libs.spring.kafka)
    testImplementation(libs.spring.batch.core)
    testImplementation(libs.resilience4j.circuitbreaker)
    testImplementation(libs.awssdk.s3)
}
