import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

group = "com.posthog"
version = "1.0.0"

dependencies {
    implementation(project(mapOf("path" to ":posthog")))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.postHogConfig()
}

kotlin {
    jvmToolchain(PosthogBuildConfig.Build.JAVA_VERSION.majorVersion.toInt())
}
