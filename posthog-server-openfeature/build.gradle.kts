@file:Suppress("ktlint:standard:max-line-length")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

version = properties["serverOpenFeatureVersion"].toString()

plugins {
    `java-library`
    kotlin("jvm")
    id("com.android.lint")

    // publish
    `maven-publish`
    signing
    id("org.jetbrains.dokka")

    // tests
    id("org.jetbrains.kotlinx.kover")
}

// the OpenFeature Java SDK requires Java 11
java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val dokkaJavadocJar by tasks.register<Jar>("dokkaJavadocJar") {
    dependsOn(tasks.dokkaJavadoc)
    from(tasks.dokkaJavadoc.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
}

val dokkaHtmlJar by tasks.register<Jar>("dokkaHtmlJar") {
    dependsOn(tasks.dokkaHtml)
    from(tasks.dokkaHtml.flatMap { it.outputDirectory })
    archiveClassifier.set("html-doc")
}

tasks.named("assemble") {
    dependsOn(dokkaJavadocJar, dokkaHtmlJar)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(dokkaJavadocJar)
            artifact(dokkaHtmlJar)

            postHogConfig(project.name, project.version.toString())
            pom.postHogConfig(
                project.name,
                moduleDescription = "Official PostHog OpenFeature provider for server-side JVM applications",
            )
        }
    }
    signing.postHogConfig("maven", this)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.postHogConfig()
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

kotlin {
    explicitApi()
}

configure<SourceSetContainer> {
    test {
        java.srcDir("src/test/java")
    }
}

dependencies {
    add("dokkaJavadocPlugin", "org.jetbrains.dokka:javadoc-plugin:${properties["dokkaVersion"]}")

    api(project(":posthog-server"))
    api("dev.openfeature:sdk:${PosthogBuildConfig.Dependencies.OPENFEATURE}")

    api(kotlin("stdlib-jdk8", PosthogBuildConfig.Kotlin.KOTLIN))

    implementation("com.google.code.gson:gson:${PosthogBuildConfig.Dependencies.GSON}")

    // tests
    testImplementation("org.mockito.kotlin:mockito-kotlin:${PosthogBuildConfig.Dependencies.MOCKITO}")
    testImplementation("org.mockito:mockito-inline:${PosthogBuildConfig.Dependencies.MOCKITO_INLINE}")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${PosthogBuildConfig.Kotlin.KOTLIN}")
}

tasks.javadoc {
    if (JavaVersion.current().isJava9Compatible) {
        (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    }
}
