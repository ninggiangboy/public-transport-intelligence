// Conventions for every Java module (DOC-11 §1.2, DOC-44 §3.1).
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.SpotBugsTask

plugins {
    `java-library`
    `java-test-fixtures`
    `jvm-test-suite`
    jacoco
    checkstyle
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()
fun version(alias: String) = libs.findVersion(alias).get().requiredVersion

val pti = extensions.create<PtiExtension>("pti")
pti.unitLineCoverage.convention(0.0)

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial,-this-escape", "-Werror"))
}

// Spring Boot BOM for every source set; libraries it manages carry no version (DOC-11 §0).
sourceSets.configureEach {
    listOf(implementationConfigurationName, annotationProcessorConfigurationName, compileOnlyConfigurationName)
        .forEach { dependencies.add(it, dependencies.platform(lib("spring-boot-dependencies"))) }
}

// Backend tests read three locations outside backend/ through this property only (ADR-0030).
val repoRoot: String = rootDir.parentFile.absolutePath

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter()
            dependencies {
                implementation(lib("assertj-core"))
                implementation(lib("mockito-core"))
            }
            targets.configureEach {
                testTask.configure {
                    useJUnitPlatform { excludeTags("quarantine") }
                    // Unit tests run classes concurrently (DOC-44 §3.1).
                    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
                    systemProperty("junit.jupiter.execution.parallel.mode.default", "same_thread")
                    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
                }
            }
        }

        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
                implementation(lib("assertj-core"))
                implementation(lib("awaitility"))
                implementation(lib("testcontainers-junit-jupiter"))
            }
            targets.configureEach {
                testTask.configure {
                    val includeTags = providers.gradleProperty("includeTags").orNull
                    useJUnitPlatform {
                        if (includeTags != null) includeTags(*includeTags.split(",").toTypedArray())
                        else excludeTags("slow", "quarantine")
                    }
                    maxParallelForks = 1
                    shouldRunAfter(tasks.named("test"))
                }
            }
        }
    }
}

configurations.named("integrationTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
configurations.named("integrationTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }

val integrationTestSourceSet = sourceSets.named("integrationTest")
tasks.register<Test>("slowTest") {
    description = "Runs integration tests tagged 'slow' (DOC-44 §3.1)."
    group = "verification"
    testClassesDirs = integrationTestSourceSet.get().output.classesDirs
    classpath = integrationTestSourceSet.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("slow")
        excludeTags("quarantine")
    }
    maxParallelForks = 1
    shouldRunAfter(tasks.named("test"))
}

tasks.withType<Test>().configureEach {
    systemProperty("pti.repo-root", repoRoot)
    systemProperty("user.timezone", "UTC")
    systemProperty("user.language", "")
    systemProperty("user.country", "")
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Coverage (DOC-44 §7). `check` runs the unit-test gate only; integration tests are not part of `check`.
jacoco {
    toolVersion = version("jacoco")
}

val coverageExcludes = listOf("**/*Application.class", "**/*Properties.class")

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
    classDirectories.setFrom(sourceSets.main.map { it.output.classesDirs.asFileTree.matching { exclude(coverageExcludes) } })
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(sourceSets.main.map { it.output.classesDirs.asFileTree.matching { exclude(coverageExcludes) } })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = pti.unitLineCoverage.map { it.toBigDecimal() }.get()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

spotless {
    java {
        target("src/*/java/**/*.java")
        palantirJavaFormat()
        removeUnusedImports()
        forbidWildcardImports()
    }
}

checkstyle {
    toolVersion = version("checkstyle")
    configDirectory = rootProject.layout.projectDirectory.dir("config/checkstyle")
    maxWarnings = 0
}

spotbugs {
    toolVersion = version("spotbugs")
    excludeFilter = rootProject.layout.projectDirectory.file("config/spotbugs/exclude.xml")
    reportLevel = Confidence.MEDIUM
}

// Only production code is analysed by SpotBugs.
tasks.withType<SpotBugsTask>().configureEach {
    enabled = name == "spotbugsMain"
    reports.maybeCreate("html").required = true
}
