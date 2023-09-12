// type safe project access
// enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

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
// rootProject.buildFileName = "build.gradle.kts"

include(":posthog")
include(":posthog-android")

// samples
include(":posthog-samples:posthog-android-sample")
