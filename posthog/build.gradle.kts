import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
}

configure<JavaPluginExtension> {
    sourceCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
    targetCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
}

buildConfig {
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

            postHogConfig(project.name, properties)

            pom.postHogConfig(project.name)
        }
    }
    signing.postHogConfig("maven", this)
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = PosthogBuildConfig.Build.JAVA_VERSION.toString()
    kotlinOptions.languageVersion = PosthogBuildConfig.Kotlin.KOTLIN_COMPATIBILITY
}

kotlin {
    explicitApi()
}

dependencies {
    implementation(kotlin("stdlib-jdk8", KotlinCompilerVersion.VERSION))

    implementation("com.google.code.gson:gson:2.10.1")

    implementation(platform("com.squareup.okhttp3:okhttp-bom:4.11.0"))
    implementation("com.squareup.okhttp3:okhttp")
}

tasks.javadoc {
    if (JavaVersion.current().isJava9Compatible) {
        (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    }
}
