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
include(":posthog-server")

// samples
include(":posthog-samples:posthog-android-sample")
include(":posthog-samples:posthog-java-sample")
include(":posthog-samples:posthog-spring-sample")

// Include the plugin as a composite build
includeBuild("posthog-android-gradle-plugin")
