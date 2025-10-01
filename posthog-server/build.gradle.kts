@file:Suppress("ktlint:standard:max-line-length")

import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

version = properties["serverVersion"].toString()

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
    packageName("com.posthog")
    buildConfigField("String", "SDK_NAME", "\"posthog-server\"")
    buildConfigField("String", "VERSION_NAME", "\"${project.version}\"")
}

java {
    withSourcesJar()
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
                moduleDescription = "Official PostHog SDK for server-side JVM applications",
            )
        }
    }
    signing.postHogConfig("maven", this)
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.postHogConfig()
}

kotlin {
    explicitApi()
    jvmToolchain(PosthogBuildConfig.Build.JAVA_VERSION.majorVersion.toInt())
}

configure<SourceSetContainer> {
    test {
        java.srcDir("src/test/java")
    }
}

animalsniffer {
    ignore("java.util.HashMap")
}

dependencies {
    // Depend on posthog-core module (not posthog-android)
    api(project(":posthog"))

    implementation(kotlin("stdlib-jdk8", KotlinCompilerVersion.VERSION))

    implementation("com.google.code.gson:gson:${PosthogBuildConfig.Dependencies.GSON}")

    implementation(platform("com.squareup.okhttp3:okhttp-bom:${PosthogBuildConfig.Dependencies.OKHTTP}"))
    implementation("com.squareup.okhttp3:okhttp")
    compileOnly("org.codehaus.mojo:animal-sniffer-annotations:${PosthogBuildConfig.Plugins.ANIMAL_SNIFFER_SDK_ANNOTATION}")

    // compatibility
    signature("org.codehaus.mojo.signature:java18:${PosthogBuildConfig.Plugins.SIGNATURE_JAVA18}@signature")

    // tests
    testImplementation("org.mockito.kotlin:mockito-kotlin:${PosthogBuildConfig.Dependencies.MOCKITO}")
    testImplementation("org.mockito:mockito-inline:${PosthogBuildConfig.Dependencies.MOCKITO_INLINE}")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${PosthogBuildConfig.Kotlin.KOTLIN}")
    testImplementation("com.squareup.okhttp3:mockwebserver:${PosthogBuildConfig.Dependencies.OKHTTP}")
}

tasks.javadoc {
    if (JavaVersion.current().isJava9Compatible) {
        (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    }
}
