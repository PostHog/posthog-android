import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

version = properties["androidPluginVersion"].toString()

plugins {
    `kotlin-dsl`
    id("java-gradle-plugin")

    // publish
    `maven-publish`
    signing
    id("org.jetbrains.dokka")
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

publishing {
    publications {
        // Configure the automatic plugin publication
        withType<MavenPublication> {
            groupId = "com.posthog"
            version = project.version.toString()
            // Add dokka artifacts to the plugin publication
            artifact(dokkaJavadocJar)
            artifact(dokkaHtmlJar)
        }
    }

    publications.withType<MavenPublication> {
        pom {
            val repo = "posthog-android"
            name.set(project.name)
            description.set("PostHog Android Gradle Plugin")
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
}

// Fix task dependencies for signing
afterEvaluate {
    tasks.withType<PublishToMavenRepository> {
        dependsOn(tasks.withType<Sign>())
    }

    // Fix specific plugin marker publication dependencies
    tasks.findByName("publishPluginMavenPublicationToMavenLocal")?.dependsOn(
        "signPostHogAndroidPluginPluginMarkerMavenPublication"
    )
    tasks.findByName("publishPostHogAndroidPluginPluginMarkerMavenPublicationToMavenLocal")?.dependsOn(
        "signPostHogAndroidPluginPluginMarkerMavenPublication",
        "signPluginMavenPublication"
    )
}

dependencies {
    compileOnly(gradleApi())
    // pinned to 8.0.x so we compile against the min. supported version.
    compileOnly("com.android.tools.build:gradle:8.0.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.22")
}
