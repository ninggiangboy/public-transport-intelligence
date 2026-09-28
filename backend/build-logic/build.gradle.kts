plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gradle.plugin.spring.boot)
    implementation(libs.gradle.plugin.spotless)
    implementation(libs.gradle.plugin.spotbugs)
    implementation(libs.gradle.plugin.jib)
}
