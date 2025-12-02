import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI

val postHogGroupId = "com.posthog"
group = postHogGroupId
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
    id("io.github.gradle-nexus.publish-plugin") version "1.3.0"
}

java {
    withSourcesJar()
}

kotlin {
    jvmToolchain(17)
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

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
        languageVersion = "1.6"
        allWarningsAsErrors = true
        apiVersion = "1.6"
        freeCompilerArgs += "-Xexplicit-api=strict"
    }
}

kotlin {
    explicitApi()
    jvmToolchain(JavaVersion.VERSION_17.majorVersion.toInt())
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

dependencies {
    compileOnly(gradleApi())
    // pinned to 8.0.x so we compile against the min. supported version.
    compileOnly("com.android.tools.build:gradle:8.0.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.22")
}
