plugins {
    kotlin("jvm")
    application
}

group = "com.posthog.compliance"
version = "1.0.0"

dependencies {
    implementation(project(":sdk_compliance_adapter:common"))
    implementation(project(":posthog-server"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${PosthogBuildConfig.Kotlin.KOTLIN}")
    testImplementation("com.squareup.okhttp3:mockwebserver:${PosthogBuildConfig.Dependencies.OKHTTP}")
    testImplementation("com.google.code.gson:gson:${PosthogBuildConfig.Dependencies.GSON}")
}

application {
    mainClass.set("com.posthog.compliance.MainKt")
}

kotlin {
    jvmToolchain(PosthogBuildConfig.Build.JDK_VERSION)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.postHogConfig(false)
}

java {
    sourceCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
    targetCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
}

// Disable API validation for test adapter
tasks.matching { it.name == "apiCheck" || it.name == "apiDump" }.configureEach {
    enabled = false
}
