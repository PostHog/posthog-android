import com.android.build.gradle.LibraryExtension
import io.github.gradlenexus.publishplugin.NexusPublishExtension
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.findByType
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.dokka.gradle.DokkaTask
import java.net.URI

object PostHogPublishConfig {
    val versionNameProperty = "versionName"
}

fun Project.publishingAndroidConfig() {
    val projectName = name

    val androidExtension =
        extensions.findByType(LibraryExtension::class.java)
    if (androidExtension == null) {
        logger.error("Missing android library extension for $projectName")
        return
    }

    androidExtension.publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    afterEvaluate {
        val publishingExtension = extensions.findByType(PublishingExtension::class)
        val signingExtension = extensions.findByType(SigningExtension::class)
        if (publishingExtension == null || signingExtension == null) {
            System.err.println("Missing publishing or signing extension for $projectName")
            return@afterEvaluate
        }

        publishingExtension.apply {
            publications.create("release", MavenPublication::class.java) {
                from(components.getByName("release"))

                postHogConfig(projectName, version.toString())

                pom.postHogConfig(projectName, moduleDescription = "Official PostHog SDK for Android applications")
            }
        }

        signingExtension.postHogConfig("release", publishingExtension)
    }
}

fun Project.javadocConfig() {
    tasks.withType(DokkaTask::class.java).configureEach {
        val toOutputDirectory = file("${layout.buildDirectory.asFile.get()}/reports/javadoc")
        outputDirectory.set(toOutputDirectory)
        doFirst {
            if (!toOutputDirectory.exists()) {
                toOutputDirectory.mkdirs()
            }
        }
    }
}

fun MavenPom.postHogConfig(
    projectName: String,
    repo: String = "posthog-android",
    moduleDescription: String,
) {
    name.set(projectName)
    description.set(moduleDescription)
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

fun MavenPublication.postHogConfig(
    projectName: String,
    version: String,
) {
    groupId = "com.posthog"
    artifactId = projectName
    this.version = version
}

fun SigningExtension.postHogConfig(
    variantName: String,
    publishingExtension: PublishingExtension,
) {
    val privateKey = System.getenv("GPG_PRIVATE_KEY")
    val password = System.getenv("GPG_PASSPHRASE")
    // releases are only signed on CI, so skip this locally
    isRequired = PosthogBuildConfig.isCI()
    useInMemoryPgpKeys(privateKey, password)
    sign(publishingExtension.publications.getByName(variantName))
}

fun NexusPublishExtension.postHogConfig() {
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
