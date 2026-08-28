import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI
import java.util.Properties

// shared versions — loaded from root gradle.properties (single source of truth)
val versions =
    Properties().apply {
        file("../gradle.properties").inputStream().use { load(it) }
    }

val postHogGroupId = "com.posthog"
group = postHogGroupId

dependencyLocking {
    lockAllConfigurations()
}
version = properties["androidPluginVersion"].toString()

// Extension function for common POM configuration
fun MavenPom.configurePom(
    projectName: String,
    projectDescription: String,
) {
    val repo = "posthog-android"
    name.set(projectName)
    description.set(projectDescription)
    url.set("https://github.com/postHog/$repo")

    licenses {
        license {
            name.set("MIT")
            url.set("http://opensource.org/licenses/MIT")
        }
    }
    organization {
        name.set("PostHog")
        url.set("https://posthog.com")
    }
    developers {
        developer {
            name.set("PostHog")
            email.set("engineering@posthog.com")
            organization.set("PostHog")
            organizationUrl.set("https://posthog.com")
        }
    }

    scm {
        url.set("https://github.com/postHog/$repo")
        connection.set("scm:git:git@github.com:PostHog/$repo.git")
        developerConnection.set("scm:git:git@github.com:PostHog/$repo.git")
    }
}

plugins {
    `kotlin-dsl`
    id("java-gradle-plugin")

    // publish
    `maven-publish`
    signing
    id("org.jetbrains.dokka")
    id("io.github.gradle-nexus.publish-plugin")
}

java {
    withSourcesJar()
}

kotlin {
    jvmToolchain((versions["jdkVersion"] as String).toInt())
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

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(
            JvmTarget.fromTarget(JavaVersion.toVersion(versions["jdkVersion"] as String).toString()),
        )
        val compatVersion = KotlinVersion.fromVersion(versions["kotlinCompatibility"] as String)
        languageVersion.set(compatVersion)
        allWarningsAsErrors.set(true)
        apiVersion.set(compatVersion)
        freeCompilerArgs.addAll("-Xexplicit-api=strict", "-Xsuppress-version-warnings")
    }
}

kotlin {
    explicitApi()
    jvmToolchain((versions["jdkVersion"] as String).toInt())
}

configure<SourceSetContainer> {
    test {
        java.srcDir("src/test/java")
    }
}

tasks.javadoc {
    if (JavaVersion.current().isJava9Compatible) {
        (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    }
}

gradlePlugin {
    plugins {
        create("postHogAndroidPlugin") {
            id = "com.posthog.android"
            implementationClass = "com.posthog.android.PostHogAndroidGradlePlugin"
            displayName = "PostHog Android Gradle Plugin"
        }
    }
}

nexusPublishing {
    repositories {
        sonatype {
            stagingProfileId.set("1dbefd58b2cdd")
            // created using manoel at posthog.com
            val sonatypeUsername = System.getenv("SONATYPE_USERNAME")
            val sonatypePassword = System.getenv("SONATYPE_PASSWORD")
            if (sonatypeUsername != null) username.set(sonatypeUsername)
            if (sonatypePassword != null) password.set(sonatypePassword)
            // https://central.sonatype.org/news/20250326_ossrh_sunset/
            nexusUrl.set(URI("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(URI("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}

publishing {
    publications {
        // Configure all publications with common POM data
        withType<MavenPublication> {
            groupId = postHogGroupId
            version = project.version.toString()
        }
    }

    // Configure specific publications after they're created
    afterEvaluate {
        publications.named<MavenPublication>("pluginMaven") {
            artifact(dokkaJavadocJar)
            artifact(dokkaHtmlJar)

            pom {
                configurePom(
                    "PostHog Android Gradle Plugin",
                    "PostHog Android Gradle Plugin for build-time integration",
                )
            }
        }

        publications.named<MavenPublication>("postHogAndroidPluginPluginMarkerMaven") {
            pom {
                configurePom(
                    "PostHog Android Gradle Plugin (Gradle Plugin)",
                    "Gradle plugin marker for PostHog Android Gradle Plugin",
                )
            }
        }
    }
}

signing {
    // created using manoel at posthog.com
    val privateKey = System.getenv("GPG_PRIVATE_KEY")
    val password = System.getenv("GPG_PASSPHRASE")
    // releases are only signed on CI, so skip this locally
    isRequired = System.getenv("CI")?.toBoolean() ?: false
    useInMemoryPgpKeys(privateKey, password)
    // Sign all publications
    sign(publishing.publications)
}

// A dependency published here lands on the consumer's root buildscript classpath, which every module
// of their build shares and where Gradle resolves conflicts by taking the highest version — so it sets
// the version their whole build compiles against, not just ours. The plugin therefore publishes none,
// and checkNoPublishedRuntimeDependencies holds it to that.
dependencies {
    compileOnly(gradleApi())
    // pinned to 8.0.x so we compile against the min. supported version.
    compileOnly("com.android.tools.build:gradle:8.0.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${versions["kotlinVersion"]}")
}

val checkNoPublishedRuntimeDependencies =
    tasks.register("checkNoPublishedRuntimeDependencies") {
        description = "Fails if the plugin publishes runtime dependencies."
        group = "verification"
        // runtimeElements is the variant that gets published, and constraints on it raise a consumer's
        // resolved version just as dependencies do. Read at configuration time: resolving inside the
        // task action would capture the configuration itself, which the configuration cache rejects.
        val published =
            configurations.named("runtimeElements").map { runtimeElements ->
                runtimeElements.allDependencies.map { "${it.group}:${it.name}:${it.version}" } +
                    runtimeElements.allDependencyConstraints.map { "${it.group}:${it.name}:${it.version}" }
            }.get()
        doLast {
            check(published.isEmpty()) {
                "The plugin must publish no runtime dependencies, found: " + published.joinToString()
            }
        }
    }

tasks.named("check") {
    dependsOn(checkNoPublishedRuntimeDependencies)
}

// Functional tests run the plugin through Gradle TestKit against real AGP
// versions (the oldest supported and a current one), because the merged
// native libs directory and its producer task are AGP internals that can
// move between releases.
val functionalTest: SourceSet by sourceSets.creating

dependencies {
    "functionalTestImplementation"(gradleTestKit())
    "functionalTestImplementation"("org.jetbrains.kotlin:kotlin-test-junit:${versions["kotlinVersion"]}")
}

gradlePlugin.testSourceSets(functionalTest)

val functionalTestTask =
    tasks.register<Test>("functionalTest") {
        description = "Runs the functional tests."
        group = "verification"
        testClassesDirs = functionalTest.output.classesDirs
        classpath = functionalTest.runtimeClasspath
        useJUnit()
    }

tasks.named("check") {
    dependsOn(functionalTestTask)
}
