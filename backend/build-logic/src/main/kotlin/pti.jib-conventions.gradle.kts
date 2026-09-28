// Container image conventions for runnable modules (DOC-11 §1.2, DOC-41 §5).
plugins {
    id("com.google.cloud.tools.jib")
}

// eclipse-temurin:25-jre, multi-arch index digest.
val baseImage = "eclipse-temurin:25-jre@sha256:8da0490fa9a3c26867012019565948eef0ee69438f5c75ac28146967bae984b5"
val registry = providers.gradleProperty("imageRegistry").getOrElse("ghcr.io/ninggiangboy")
val imageTag = providers.gradleProperty("imageTag").getOrElse("local")
val hostPlatform = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "linux/arm64" else "linux/amd64"
// jibDockerBuild loads a single platform into the local daemon; CI pushes both with `jib -PjibPlatforms=...`.
val targetPlatforms = providers.gradleProperty("jibPlatforms").getOrElse(hostPlatform).split(",")
val ci = providers.environmentVariable("CI").isPresent

jib {
    from {
        image = baseImage
        platforms {
            targetPlatforms.forEach { p ->
                platform {
                    os = p.substringBefore("/")
                    architecture = p.substringAfter("/")
                }
            }
        }
    }
    to {
        image = "$registry/pti-${project.name}:$imageTag"
    }
    container {
        user = "1000:1000"
        labels = mapOf("org.opencontainers.image.source" to "https://github.com/ninggiangboy/public-transport-intelligence")
        // Reproducible locally; CI stamps the build time (DOC-41 §5).
        if (ci) creationTime = "USE_CURRENT_TIMESTAMP"
    }
}

// Jib 3.5 reads Task.project at execution time.
tasks.matching { it.name.startsWith("jib") }.configureEach {
    notCompatibleWithConfigurationCache("Jib 3.5 uses Task.project at execution time")
}
