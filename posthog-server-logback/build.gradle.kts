@file:Suppress("ktlint:standard:max-line-length")

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

version = properties["serverLogbackVersion"].toString()

plugins {
    `java-library`
    kotlin("jvm")
    id("com.android.lint")

    // publish
    `maven-publish`
    signing
    id("org.jetbrains.dokka")

    // plugins
    id("com.github.gmazzo.buildconfig")

    // tests
    id("org.jetbrains.kotlinx.kover")

    // compatibility
    id("ru.vyarus.animalsniffer")
}

buildConfig {
    useKotlinOutput()
    packageName("com.posthog.server.logback")
    buildConfigField("String", "SDK_NAME", "\"posthog-server-logback\"")
    buildConfigField("String", "VERSION_NAME", "\"${project.version}\"")
}

java {
    withSourcesJar()
    sourceCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
    targetCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(dokkaJavadocJar)
            artifact(dokkaHtmlJar)

            postHogConfig(project.name, project.version.toString())
            pom.postHogConfig(
                project.name,
                moduleDescription = "Logback appender that reports errors to PostHog via the server SDK",
            )
        }
    }
    signing.postHogConfig("maven", this)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.postHogConfig()
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
    // Expose the server SDK transitively: consumers configure and use a PostHog server client
    // alongside the appender, so it belongs on the compile classpath of anything depending on this.
    api(project(":posthog-server"))

    implementation(kotlin("stdlib-jdk8", PosthogBuildConfig.Kotlin.KOTLIN))

    // Logback is provided by the host application; keep it off our runtime classpath and pin a
    // conservative Java 8-compatible floor (the 1.3.x line; 1.4.x+ requires Java 11).
    compileOnly("ch.qos.logback:logback-classic:${PosthogBuildConfig.Dependencies.LOGBACK}")
    compileOnly("org.codehaus.mojo:animal-sniffer-annotations:${PosthogBuildConfig.Plugins.ANIMAL_SNIFFER_SDK_ANNOTATION}")

    // compatibility
    signature("org.codehaus.mojo.signature:java18:${PosthogBuildConfig.Plugins.SIGNATURE_JAVA18}@signature")

    // tests
    testImplementation("ch.qos.logback:logback-classic:${PosthogBuildConfig.Dependencies.LOGBACK}")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${PosthogBuildConfig.Kotlin.KOTLIN}")
    testImplementation("com.squareup.okhttp3:mockwebserver:${PosthogBuildConfig.Dependencies.OKHTTP}")
    testImplementation("com.google.code.gson:gson:${PosthogBuildConfig.Dependencies.GSON}")
}

tasks.javadoc {
    if (JavaVersion.current().isJava9Compatible) {
        (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    }
}
