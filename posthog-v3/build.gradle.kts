import com.diffplug.spotless.LineEnding
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // android
    id("com.android.application") version PosthogBuildConfig.Android.AGP apply false
    id("com.android.library") version PosthogBuildConfig.Android.AGP apply false
    kotlin("android") version PosthogBuildConfig.Kotlin.KOTLIN apply false

    // jvm
    kotlin("jvm") version PosthogBuildConfig.Kotlin.KOTLIN apply false

    // plugins
    id("com.diffplug.spotless") version PosthogBuildConfig.Plugins.SPOTLESS apply true
    id("io.gitlab.arturbosch.detekt") version PosthogBuildConfig.Plugins.DETEKT apply true
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version PosthogBuildConfig.Plugins.API_VALIDATOR apply true

    // TODO: add jacoco/codecov, dokka, gradle-versions-plugin, maven-publish
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
}
tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget = PosthogBuildConfig.Build.JAVA_VERSION.toString()
}

apiValidation {
    ignoredProjects.add("posthog-android-sample")
}
