version = properties["androidPluginVersion"].toString()

plugins {
    `kotlin-dsl`
    id("java-gradle-plugin")

    // publish
    `maven-publish`
    signing
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

dependencies {
    compileOnly(gradleApi())
    compileOnly("com.android.tools.build:gradle:8.0.2")
}
