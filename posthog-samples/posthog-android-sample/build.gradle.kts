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
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
        targetCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
    }
    kotlinOptions {
        jvmTarget = PosthogBuildConfig.Build.JAVA_VERSION.toString()
    }
    buildFeatures {
        compose = true
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
}

dependencies {
    implementation(project(mapOf("path" to ":posthog-android")))

    implementation("androidx.activity:activity-compose:1.7.2")
    implementation(platform("androidx.compose:compose-bom:2023.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}