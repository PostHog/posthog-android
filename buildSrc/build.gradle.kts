import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = JavaVersion.VERSION_17.toString()
}

dependencies {
    implementation("com.android.tools.build:gradle:8.1.1")
    // kotlin version has to match kotlinCompilerExtensionVersion
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.10")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:1.8.10")
    implementation("io.github.gradle-nexus:publish-plugin:1.3.0")
}
