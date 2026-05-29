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

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
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
