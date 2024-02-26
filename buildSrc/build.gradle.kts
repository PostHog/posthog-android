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
    // also update PosthogBuildConfig.Kotlin.KOTLIN
    val kotlinVersion = "1.9.22"
    // there's no 1.9.22 for dokka yet
    val dokkaVersion = "1.9.10"
    implementation("com.android.tools.build:gradle:8.2.2")
    // kotlin version has to match kotlinCompilerExtensionVersion
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")

    // publish
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:$dokkaVersion")
    implementation("io.github.gradle-nexus:publish-plugin:1.3.0")

    // tests
    implementation("org.jetbrains.kotlinx:kover-gradle-plugin:0.7.4")
}
