rootProject.name = "posthog-android-gradle-plugin"

pluginManagement {
    // shared versions — loaded from root gradle.properties (single source of truth)
    @Suppress("UNCHECKED_CAST")
    val versions = (Class.forName("java.util.Properties").getDeclaredConstructor().newInstance() as java.util.Properties)
    versions.load(file("../gradle.properties").inputStream())

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.dokka") version versions.getProperty("dokkaVersion")
        id("io.github.gradle-nexus.publish-plugin") version versions.getProperty("nexusPublishVersion")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
