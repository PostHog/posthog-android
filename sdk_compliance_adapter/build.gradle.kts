plugins {
    kotlin("jvm")
    application
}

group = "com.posthog.compliance"
version = "1.0.0"

dependencies {
    // PostHog Core SDK
    implementation(project(":posthog"))

    // Ktor server
    val ktorVersion = "2.3.7"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-gson:$ktorVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // OkHttp (for interceptor)
    implementation(platform("com.squareup.okhttp3:okhttp-bom:${PosthogBuildConfig.Dependencies.OKHTTP}"))
    implementation("com.squareup.okhttp3:okhttp")
}

application {
    mainClass.set("com.posthog.compliance.ComplianceAdapterKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// Disable API validation for test adapter
tasks.matching { it.name == "apiCheck" || it.name == "apiDump" }.configureEach {
    enabled = false
}
