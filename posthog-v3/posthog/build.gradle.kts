import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
    kotlin("jvm")
    id("com.android.lint")
}

java {
    sourceCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
    targetCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = PosthogBuildConfig.Build.JAVA_VERSION.toString()
}

kotlin {
    explicitApi()
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")

    implementation(platform("com.squareup.okhttp3:okhttp-bom:4.11.0"))
    implementation("com.squareup.okhttp3:okhttp")
}

// To discuss: artifact name: com.posthog.java:posthog -> com.posthog:posthog
// All pure Kotlin classes go here, it can be the new https://github.com/PostHog/posthog-java as well
