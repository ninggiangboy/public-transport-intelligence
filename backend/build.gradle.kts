// Root project: modules configure themselves through the pti.* convention plugins in build-logic.

/*
 * `affectedIntegrationTest` (DOC-41 §2, DOC-44 §3.1): runs `integrationTest` of the modules changed since
 * `-PaffectedBase=<git ref>` and of every module that depends on them. Without the property, or when shared build
 * files or the contracts read by tests change, every module is tested.
 */
val affectedBase = providers.gradleProperty("affectedBase")
val changedFiles =
    affectedBase.flatMap { base ->
        providers
            .exec {
                workingDir = rootDir.parentFile
                commandLine("git", "diff", "--name-only", "$base...HEAD")
            }
            .standardOutput
            .asText
    }

val sharedPaths =
    listOf("backend/build-logic/", "backend/gradle/", "backend/config/", "deploy/topics.yaml", "deploy/connect/")

tasks.register("affectedIntegrationTest") {
    group = "verification"
    description = "Runs integrationTest for changed modules and their dependents (-PaffectedBase=<ref>)."
    dependsOn(
        provider {
            val modules = subprojects.filter { it.tasks.findByName("integrationTest") != null }
            val files = changedFiles.orNull?.lines()?.filter { it.isNotBlank() }
            val affected =
                if (files == null ||
                    files.any { f -> sharedPaths.any { f.startsWith(it) } || f.matches(Regex("backend/[^/]+\\.kts")) }
                ) {
                    modules.toSet()
                } else {
                    val changed = modules.filter { m -> files.any { it.startsWith("backend/${m.name}/") } }.toSet()
                    val dependents = modules.associateWith { m ->
                        m.configurations
                            .flatMap { it.dependencies.withType<ProjectDependency>() }
                            .map { it.path }
                            .toSet()
                    }
                    val result = changed.toMutableSet()
                    var grew = true
                    while (grew) {
                        grew = modules.filter { it !in result && dependents.getValue(it).any { p -> result.any { r -> r.path == p } } }
                            .let { result.addAll(it) }
                    }
                    result
                }
            affected.map { "${it.path}:integrationTest" }
        }
    )
}
