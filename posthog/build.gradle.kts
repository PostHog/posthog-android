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

    // tests
    id("org.jetbrains.kotlinx.kover")

    // compatibility
    id("ru.vyarus.animalsniffer")
}

configure<JavaPluginExtension> {
    sourceCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
    targetCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
}

buildConfig {
    useKotlinOutput()
    packageName("com.posthog")
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
    kotlinOptions.postHogConfig()
}

kotlin {
    explicitApi()
}

configure<SourceSetContainer> {
    test {
        java.srcDir("src/test/java")
    }
}

tasks.compileJava {
    options.release.set(PosthogBuildConfig.Build.JAVA_VERSION.majorVersion.toInt())
}

animalsniffer {
//    com.posthog.internal.PostHogDecideRequest:6  Undefined reference (android-api-level-21-5.0.1_r2): boolean java.util.HashMap.remove(Object, Object)
//    com.posthog.internal.PostHogDecideRequest:6  Undefined reference (android-api-level-21-5.0.1_r2): Object java.util.HashMap.getOrDefault(Object, Object)
// we don't use these methods, so ignore them
    ignore.add("java.util.HashMap")
}

dependencies {
    implementation(kotlin("stdlib-jdk8", KotlinCompilerVersion.VERSION))

    implementation("com.google.code.gson:gson:${PosthogBuildConfig.Dependencies.GSON}")

    implementation(platform("com.squareup.okhttp3:okhttp-bom:${PosthogBuildConfig.Dependencies.OKHTTP}"))
    implementation("com.squareup.okhttp3:okhttp")

    // compatibility
    signature("org.codehaus.mojo.signature:java18:1.0@signature")
    signature("net.sf.androidscents.signature:android-api-level-${PosthogBuildConfig.Android.MIN_SDK}:5.0.1_r2@signature")

    // tests
    testImplementation("org.mockito.kotlin:mockito-kotlin:${PosthogBuildConfig.Dependencies.MOCKITO}")
    testImplementation("org.mockito:mockito-inline:${PosthogBuildConfig.Dependencies.MOCKITO_INLINE}")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${PosthogBuildConfig.Kotlin.KOTLIN}")
    testImplementation("com.squareup.okhttp3:mockwebserver:${PosthogBuildConfig.Dependencies.OKHTTP}")
    testImplementation(project(mapOf("path" to ":posthog-android")))
}

tasks.javadoc {
    if (JavaVersion.current().isJava9Compatible) {
        (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    }
}
