plugins {
    id("pti.java-conventions")
}

description = "Detectors and scheduled analytics shared by etl (ADR-0014)."

dependencies {
    api(project(":common"))
}
