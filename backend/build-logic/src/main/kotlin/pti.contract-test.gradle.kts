// Adds the `contractTest` suite (DOC-44 §3.1, §9) to etl, source-simulator, api and triage-worker.
plugins {
    id("pti.java-conventions")
}

val libs = the<VersionCatalogsExtension>().named("libs")

testing {
    suites {
        register<JvmTestSuite>("contractTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
                implementation(libs.findLibrary("assertj-core").get())
                implementation(libs.findLibrary("testcontainers-junit-jupiter").get())
            }
            targets.configureEach {
                testTask.configure {
                    useJUnitPlatform { excludeTags("quarantine") }
                    maxParallelForks = 1
                    shouldRunAfter(tasks.named("test"))
                }
            }
        }
    }
}

configurations.named("contractTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
configurations.named("contractTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }
