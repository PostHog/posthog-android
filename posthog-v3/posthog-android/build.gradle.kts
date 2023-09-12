plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.posthog.android"
    compileSdk = PosthogBuildConfig.Android.COMPILE_SDK

    defaultConfig {
        minSdk = PosthogBuildConfig.Android.MIN_SDK

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
    kotlinOptions {
        jvmTarget = PosthogBuildConfig.Build.JAVA_VERSION.toString()
        // TODO: Do we need kotlinOptions.languageVersion?
    }

    testOptions {
        animationsDisabled = true
    }

    lint {
        warningsAsErrors = true
        checkDependencies = true

        // lint runs only for debug build
        checkReleaseBuilds = false
    }

    androidComponents.beforeVariants {
        it.enable = !PosthogBuildConfig.shouldSkipDebugVariant(it.name)
    }
}

kotlin {
    explicitApi()
}

dependencies {

    api(project(mapOf("path" to ":posthog")))

//    testImplementation("junit:junit:4.13.2")
//    androidTestImplementation("androidx.test.ext:junit:1.1.5")
//    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
