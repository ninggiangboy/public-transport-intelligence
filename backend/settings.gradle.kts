// Gradle build for every Java module (ADR-0030). Run from backend/ or via the root Makefile.
pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "pti"

include(
    "common",
    "analytics",
    "etl",
    "triage-worker",
    "api",
    "source-simulator",
    "db",
)
