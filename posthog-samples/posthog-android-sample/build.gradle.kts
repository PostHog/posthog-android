plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.posthog.android.sample"
    compileSdk = PosthogBuildConfig.Android.COMPILE_SDK

    defaultConfig {
        applicationId = "com.posthog.android.sample"
        minSdk = PosthogBuildConfig.Android.MIN_SDK
        targetSdk = PosthogBuildConfig.Android.TARGET_SDK
        versionCode = 1
        versionName = "1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
        targetCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
    }

    kotlinOptions.postHogConfig(false)

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = PosthogBuildConfig.Kotlin.COMPILER_EXTENSION_VERSION
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidComponents.beforeVariants {
        it.enable = !PosthogBuildConfig.shouldSkipDebugVariant(it.name)
    }

    lint {
        warningsAsErrors = false
        abortOnError = false
        ignoreTestSources = true

        // lint runs only for debug build
        checkReleaseBuilds = false

        baseline = File("lint-baseline.xml")
    }
}

kotlin {
    val javaVersion = JavaLanguageVersion.of(PosthogBuildConfig.Build.JAVA_VERSION.majorVersion).asInt()
    jvmToolchain(javaVersion)
}

dependencies {
    implementation(project(mapOf("path" to ":posthog-android")))

    implementation("androidx.activity:activity-compose:1.7.2")
    implementation(platform("androidx.compose:compose-bom:2023.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation(platform("com.squareup.okhttp3:okhttp-bom:${PosthogBuildConfig.Dependencies.OKHTTP}"))
    implementation("com.squareup.okhttp3:okhttp")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.12")
}
