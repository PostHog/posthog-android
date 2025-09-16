pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
include(":posthog-samples:posthog-java-sample")
