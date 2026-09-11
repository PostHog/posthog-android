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
include(":posthog-android-surveys-compose")
include(":posthog-server")

// Test-only HTTP adapter; opt in so ordinary library builds are unchanged.
if (providers.gradleProperty("compliance").isPresent) {
    include(":sdk_compliance_adapter", ":sdk_compliance_adapter:common")
    if (providers.gradleProperty("complianceAndroid").isPresent) {
        include(":sdk_compliance_adapter:android")
    }
}

// samples
include(":posthog-samples:posthog-android-sample")
include(":posthog-samples:posthog-java-sample")
include(":posthog-samples:posthog-spring-sample")

// Include the plugin as a composite build
includeBuild("posthog-android-gradle-plugin")
