import java.util.Properties

plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

// shared versions — loaded from root gradle.properties (single source of truth)
val versions =
    Properties().apply {
        file("../gradle.properties").inputStream().use { load(it) }
    }

kotlin {
    jvmToolchain((versions["jdkVersion"] as String).toInt())
}

dependencies {
    implementation("com.android.tools.build:gradle:${versions["agpVersion"]}")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${versions["kotlinVersion"]}")
    // Compose compiler plugin for Kotlin 2.0+ (used by sample app)
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${versions["kotlinVersion"]}")

    // publish
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:${versions["dokkaVersion"]}")
    implementation("io.github.gradle-nexus:publish-plugin:${versions["nexusPublishVersion"]}")

    // tests
    implementation("org.jetbrains.kotlinx:kover-gradle-plugin:${versions["koverVersion"]}")
}
