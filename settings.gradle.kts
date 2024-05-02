pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PostHog"

include(":posthog")
include(":posthog-android")

// samples
include(":posthog-samples:posthog-android-sample")
include("posthog-samples:posthog-console-sample")
findProject(":posthog-samples:posthog-console-sample")?.name = "posthog-console-sample"
