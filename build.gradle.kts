import com.diffplug.spotless.LineEnding
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask

// Top-level build file where you can add configuration options common to all sub-projects/modules.

dependencyLocking {
    lockAllConfigurations()
}

plugins {
    // release
    id("io.github.gradle-nexus.publish-plugin")

    // plugins
    id("com.diffplug.spotless") version PosthogBuildConfig.Plugins.SPOTLESS apply true
    id("io.gitlab.arturbosch.detekt") version PosthogBuildConfig.Plugins.DETEKT apply true
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version PosthogBuildConfig.Plugins.API_VALIDATOR apply true
    id("com.github.gmazzo.buildconfig") version PosthogBuildConfig.Plugins.BUILD_CONFIG apply false
    id("ru.vyarus.animalsniffer") version PosthogBuildConfig.Plugins.ANIMAL_SNIFFER apply false

    // TODO: add jacoco/codecov, gradle-versions-plugin
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")

    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.withType<Test>().configureEach {
        jvmArgs("-Xshare:off")

        // Diagnostic: PostHogAndroidEventSnapshotsTest fails on the Linux CI runner but passes on
        // macOS, and the default logging prints only the assertion's location. FULL prints the
        // expected/actual maps; the per-test events let the CI test list be diffed against a local
        // run (569 tests on CI vs 541 locally). Revert once the mismatch is identified.
        testLogging {
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStackTraces = true
            events("passed", "skipped", "failed")
        }
    }
}

spotless {
    lineEndings = LineEnding.UNIX
    kotlin {
        target("**/*.kt")
        targetExclude("**/bin/**", "**/node_modules/**", "**/build/**")

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
    // Pre-1.0 (0.x) module — public surface may change between minor versions until 1.0.0.
    ignoredProjects.add("posthog-android-surveys-compose")
}

nexusPublishing.postHogConfig()
