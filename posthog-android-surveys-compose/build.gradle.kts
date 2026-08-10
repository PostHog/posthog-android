@file:Suppress("ktlint:standard:max-line-length")

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

version = properties["surveysComposeVersion"].toString()

plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")

    // publish
    `maven-publish`
    signing
    id("org.jetbrains.dokka")
}

android {
    namespace = "com.posthog.android.surveys.compose"
    compileSdk = PosthogBuildConfig.Android.COMPILE_SDK

    defaultConfig {
        minSdk = PosthogBuildConfig.Android.MIN_SDK
    }

    buildTypes {
        release {
            consumerProguardFiles("consumer-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
        targetCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
    }

    lint {
        warningsAsErrors = true
        checkDependencies = true
        abortOnError = true
        ignoreTestSources = true

        // lint runs only for debug build
        checkReleaseBuilds = false

        baseline = File("lint-baseline.xml")
        disable.add("GradleDependency")
    }

    androidComponents.beforeVariants {
        it.enable = !PosthogBuildConfig.shouldSkipDebugVariant(it.name)
    }
}

kotlin {
    jvmToolchain(PosthogBuildConfig.Build.JDK_VERSION)
    // strict=false: Compose's @Preview/@Composable interact poorly with -Xexplicit-api=strict
    compilerOptions.postHogConfig(strict = false)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.postHogConfig(false)
}

dependencies {
    // runtime — pulls in :posthog transitively
    api(project(mapOf("path" to ":posthog-android")))
    implementation(kotlin("stdlib-jdk8", PosthogBuildConfig.Kotlin.KOTLIN))

    implementation("androidx.lifecycle:lifecycle-process:${PosthogBuildConfig.Dependencies.LIFECYCLE}")
    // ComponentDialog — hosts the survey sheet in its own window above the host activity.
    implementation("androidx.activity:activity:${PosthogBuildConfig.Dependencies.ANDROIDX_ACTIVITY}")

    // Compose — pin to the base of the current stable Material 3 line (1.3.0). Gradle resolves
    // Compose to the highest version across the app, so this only "forces up" hosts on an older
    // Compose and never downgrades a host on a newer one. We deliberately don't go older (e.g. 1.2.x):
    // Material 3 has had binary-breaking API changes (e.g. ModalBottomSheetProperties), so compiling
    // against an older line risks a runtime crash on the 1.3.x hosts that are common today.
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // tests
    testImplementation("junit:junit:${PosthogBuildConfig.Dependencies.ANDROIDX_JUNIT}")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${PosthogBuildConfig.Kotlin.KOTLIN}")
}

project.publishingAndroidConfig()
project.javadocConfig()
