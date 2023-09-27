import org.jetbrains.kotlin.config.KotlinCompilerVersion

plugins {
    id("com.android.library")
    kotlin("android")

    // publish
    `maven-publish`
    signing
    id("org.jetbrains.dokka")

    // tests
    id("org.jetbrains.kotlinx.kover")
}

android {
    namespace = "com.posthog.android"
    compileSdk = PosthogBuildConfig.Android.COMPILE_SDK

    defaultConfig {
        minSdk = PosthogBuildConfig.Android.MIN_SDK

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        buildFeatures {
            buildConfig = true
        }

        buildConfigField("String", "VERSION_NAME", "\"${project.version}\"")
    }

    buildTypes {
        release {
            consumerProguardFiles("proguard-rules.pro")
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
    }

    androidComponents.beforeVariants {
        it.enable = !PosthogBuildConfig.shouldSkipDebugVariant(it.name)
    }

    kotlinOptions.postHogConfig()
}

dependencies {
    // runtime
    api(project(mapOf("path" to ":posthog")))
    implementation(kotlin("stdlib-jdk8", KotlinCompilerVersion.VERSION))
    implementation("androidx.lifecycle:lifecycle-process:${PosthogBuildConfig.Dependencies.LIFECYCLE}")
    implementation("androidx.lifecycle:lifecycle-common-java8:${PosthogBuildConfig.Dependencies.LIFECYCLE}")

    // tests
    testImplementation("org.mockito.kotlin:mockito-kotlin:${PosthogBuildConfig.Dependencies.MOCKITO}")
    testImplementation("org.mockito:mockito-inline:${PosthogBuildConfig.Dependencies.MOCKITO_INLINE}")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${PosthogBuildConfig.Kotlin.KOTLIN}")
    testImplementation("com.squareup.okhttp3:mockwebserver:${PosthogBuildConfig.Dependencies.OKHTTP}")
    testImplementation("androidx.test:runner:${PosthogBuildConfig.Dependencies.ANDROIDX_RUNNER}")
    testImplementation("androidx.test.ext:junit:${PosthogBuildConfig.Dependencies.ANDROIDX_JUNIT}")
    testImplementation("androidx.test:core:${PosthogBuildConfig.Dependencies.ANDROIDX_CORE}")
    testImplementation("androidx.test:core-ktx:${PosthogBuildConfig.Dependencies.ANDROIDX_CORE}")
    testImplementation("androidx.test:rules:${PosthogBuildConfig.Dependencies.ANDROIDX_CORE}")
    testImplementation("org.robolectric:robolectric:${PosthogBuildConfig.Dependencies.ROBOLECTRIC}")
}

project.publishingAndroidConfig()
project.javadocConfig()
