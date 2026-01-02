plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "com.posthog.compliance"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // PostHog Server SDK (simpler for testing)
    implementation(project(":posthog-server"))

    // Ktor server
    val ktorVersion = "2.3.7"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-gson:$ktorVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // OkHttp (for interceptor)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

application {
    mainClass.set("com.posthog.compliance.AdapterKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "11"
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
