import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
    kotlin("jvm")
    id("com.android.lint")
}

java {
    sourceCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
    targetCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = PosthogBuildConfig.Build.JAVA_VERSION.toString()
}

kotlin {
    explicitApi()
}

// To discuss: artifact name: com.posthog.java:posthog -> com.posthog:posthog
// All pure Kotlin classes go here, it can be the new https://github.com/PostHog/posthog-java as well
// Project will be Kotlin Multiplatform compatible as well
