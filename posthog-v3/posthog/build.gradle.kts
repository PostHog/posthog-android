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
