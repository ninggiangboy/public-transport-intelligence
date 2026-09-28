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
}
