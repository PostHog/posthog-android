plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.posthog.compliance.android"
    compileSdk = PosthogBuildConfig.Android.COMPILE_SDK
    defaultConfig {
        applicationId = "com.posthog.compliance.android"
        minSdk = 26
        targetSdk = PosthogBuildConfig.Android.TARGET_SDK
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
        targetCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
    }
    packaging.resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
}

kotlin {
    jvmToolchain(PosthogBuildConfig.Build.JDK_VERSION)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.postHogConfig(false)
}

dependencies {
    implementation(project(":sdk_compliance_adapter:common"))
    implementation(project(":posthog-android"))
}

tasks.matching { it.name == "apiCheck" || it.name == "apiDump" }.configureEach {
    enabled = false
}
