plugins {
    id("pti.java-conventions")
    id("pti.jib-conventions")
}

description = "Flyway migrations and the db-migrate runner (ADR-0024)."

dependencies {
    implementation(libs.flyway.core)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.slf4j.simple)

    // V5_1 must stay byte-identical to the schema shipped in the Spring Batch jar.
    testRuntimeOnly(libs.spring.batch.core)

    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
}

jib {
    to {
        image = jib.to.image!!.replace("/pti-db:", "/pti-db-migrate:")
    }
    container {
        mainClass = "dev.pti.db.DbMigrate"
    }
}
