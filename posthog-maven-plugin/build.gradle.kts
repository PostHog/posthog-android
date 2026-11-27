// The generated plugin descriptor takes its groupId from the project, and Maven refuses a plugin
// whose descriptor and coordinates disagree.
group = "com.posthog"
version = properties["mavenPluginVersion"].toString()

plugins {
    `java-library`

    // Generates META-INF/maven/plugin.xml from the @Mojo and @Parameter annotations.
    id("org.gradlex.maven-plugin-development") version "1.0.3"

    // publish
    `maven-publish`
    signing
}

java {
    withSourcesJar()
    withJavadocJar()
    sourceCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
    targetCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
}

mavenPlugin {
    name.set("PostHog Maven Plugin")
    description.set("Uploads JVM sources to PostHog so error tracking can show source code on exception frames")
    goalPrefix.set("posthog")
}

// Maven supplies its own API at runtime, so these stay off the published POM. The plugin has no
// runtime dependencies of its own, which keeps it free of version clashes with the build it runs in.
val mavenVersion = "3.9.9"
val mavenPluginToolsVersion = "3.15.1"

dependencies {
    compileOnly("org.apache.maven:maven-plugin-api:$mavenVersion")
    compileOnly("org.apache.maven:maven-core:$mavenVersion")
    compileOnly("org.apache.maven.plugin-tools:maven-plugin-annotations:$mavenPluginToolsVersion")

    testImplementation("org.apache.maven:maven-plugin-api:$mavenVersion")
    testImplementation("org.apache.maven:maven-core:$mavenVersion")
    testCompileOnly("org.apache.maven.plugin-tools:maven-plugin-annotations:$mavenPluginToolsVersion")
    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            postHogConfig(project.name, project.version.toString())
            pom.postHogConfig(
                project.name,
                moduleDescription = "Official PostHog Maven plugin: uploads JVM sources for error tracking source context",
            )
            pom.packaging = "maven-plugin"
        }
    }
    signing.postHogConfig("maven", this)
}

tasks.javadoc {
    if (JavaVersion.current().isJava9Compatible) {
        (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    }
}
