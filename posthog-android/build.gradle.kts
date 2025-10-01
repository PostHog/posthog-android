@file:Suppress("ktlint:standard:max-line-length")

import org.jetbrains.kotlin.config.KotlinCompilerVersion

version = properties["androidVersion"].toString()

plugins {
    id("com.android.library")
    kotlin("android")

    // publish
    `maven-publish`
    signing
    id("org.jetbrains.dokka")

    // tests
    id("org.jetbrains.kotlinx.kover")

    // compatibility
    id("ru.vyarus.animalsniffer")
}

android {
    namespace = "com.posthog.android"
    compileSdk = PosthogBuildConfig.Android.COMPILE_SDK

    defaultConfig {
        minSdk = PosthogBuildConfig.Android.MIN_SDK

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildFeatures {
            buildConfig = true
        }

        buildConfigField("String", "VERSION_NAME", "\"${project.version}\"")
    }

    buildTypes {
        release {
            consumerProguardFiles("consumer-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
        targetCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
    }

    testOptions {
        animationsDisabled = true
        unitTests.apply {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }

        kotlinOptions.postHogConfig(false)
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

    kotlinOptions.postHogConfig()
    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    val javaVersion = JavaLanguageVersion.of(PosthogBuildConfig.Build.JAVA_VERSION.majorVersion).asInt()
    jvmToolchain(javaVersion)
}

dependencies {
    // runtime
    api(project(mapOf("path" to ":posthog")))
    implementation(kotlin("stdlib-jdk8", KotlinCompilerVersion.VERSION))
    implementation("androidx.lifecycle:lifecycle-process:${PosthogBuildConfig.Dependencies.LIFECYCLE}")
    implementation("androidx.lifecycle:lifecycle-common-java8:${PosthogBuildConfig.Dependencies.LIFECYCLE}")
    implementation("androidx.core:core:${PosthogBuildConfig.Dependencies.ANDROIDX_CORE}")
    implementation("com.squareup.curtains:curtains:${PosthogBuildConfig.Dependencies.CURTAINS}")

    // compile only
    compileOnly("androidx.compose.ui:ui:${PosthogBuildConfig.Dependencies.ANDROIDX_COMPOSE}")

    // compatibility
    signature("org.codehaus.mojo.signature:java18:${PosthogBuildConfig.Plugins.SIGNATURE_JAVA18}@signature")
    signature(
        "net.sf.androidscents.signature:android-api-level-${PosthogBuildConfig.Android.MIN_SDK}:${PosthogBuildConfig.Plugins.ANIMAL_SNIFFER_SDK_VERSION}@signature",
    )
    signature(
        "com.toasttab.android:gummy-bears-api-${PosthogBuildConfig.Android.MIN_SDK}:${PosthogBuildConfig.Plugins.GUMMY_BEARS_API}@signature",
    )

    // tests
    testImplementation("org.mockito.kotlin:mockito-kotlin:${PosthogBuildConfig.Dependencies.MOCKITO}")
    testImplementation("org.mockito:mockito-inline:${PosthogBuildConfig.Dependencies.MOCKITO_INLINE}")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${PosthogBuildConfig.Kotlin.KOTLIN}")
    testImplementation("androidx.test:runner:${PosthogBuildConfig.Dependencies.ANDROIDX_RUNNER}")
    testImplementation("androidx.test.ext:junit:${PosthogBuildConfig.Dependencies.ANDROIDX_JUNIT}")
    testImplementation("androidx.test:core:${PosthogBuildConfig.Dependencies.ANDROIDX_CORE}")
    testImplementation("androidx.test:core-ktx:${PosthogBuildConfig.Dependencies.ANDROIDX_CORE}")
    testImplementation("androidx.test:rules:${PosthogBuildConfig.Dependencies.ANDROIDX_CORE}")
    testImplementation("org.robolectric:robolectric:${PosthogBuildConfig.Dependencies.ROBOLECTRIC}")
}

project.publishingAndroidConfig()
project.javadocConfig()
