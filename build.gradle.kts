import com.diffplug.spotless.LineEnding
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // release
    id("io.github.gradle-nexus.publish-plugin")

    // plugins
    id("com.diffplug.spotless") version PosthogBuildConfig.Plugins.SPOTLESS apply true
    id("io.gitlab.arturbosch.detekt") version PosthogBuildConfig.Plugins.DETEKT apply true
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version PosthogBuildConfig.Plugins.API_VALIDATOR apply true
    id("com.github.gmazzo.buildconfig") version PosthogBuildConfig.Plugins.BUILD_CONFIG apply false

    // TODO: add jacoco/codecov, gradle-versions-plugin
}

allprojects {
    version = properties[PostHogPublishConfig.versionNameProperty].toString()
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")
}

spotless {
    lineEndings = LineEnding.UNIX
    kotlin {
        target("**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("**/*.kts")
        ktlint()
    }
}

detekt {
    buildUponDefaultConfig = true // preconfigure defaults
    allRules = false // activate all available (even unstable) rules.
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = PosthogBuildConfig.Build.JAVA_VERSION.toString()
    languageVersion = PosthogBuildConfig.Kotlin.KOTLIN_COMPATIBILITY
}
tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget = PosthogBuildConfig.Build.JAVA_VERSION.toString()
    languageVersion = PosthogBuildConfig.Kotlin.KOTLIN_COMPATIBILITY
}

apiValidation {
    ignoredProjects.add("posthog-android-sample")
}

nexusPublishing.postHogConfig()
