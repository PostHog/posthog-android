plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // also update PosthogBuildConfig.Kotlin.KOTLIN and posthog-android-gradle-plugin
    val kotlinVersion = "2.1.10"
    // also update posthog-android-gradle-plugin
    val dokkaVersion = "1.9.20"
    implementation("com.android.tools.build:gradle:8.9.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    // Compose compiler plugin for Kotlin 2.0+ (used by sample app)
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")

    // publish
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:$dokkaVersion")
    implementation("io.github.gradle-nexus:publish-plugin:1.3.0")

    // tests
    implementation("org.jetbrains.kotlinx:kover-gradle-plugin:0.9.0")
}
