import com.diffplug.spotless.LineEnding
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // android
    id("com.android.application") version "8.1.1" apply false
    id("com.android.library") version "8.1.1" apply false
    // kotlin version has to match kotlinCompilerExtensionVersion
    kotlin("android") version "1.8.10" apply false

    // jvm
    // kotlin version has to match kotlinCompilerExtensionVersion
    kotlin("jvm") version "1.8.10" apply false

    // plugins
    id("com.diffplug.spotless") version "6.21.0" apply true
    id("io.gitlab.arturbosch.detekt") version "1.23.1" apply true
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.13.2" apply true

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
    jvmTarget = JavaVersion.VERSION_1_8.toString()
}
tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget = JavaVersion.VERSION_1_8.toString()
}

apiValidation {
    ignoredProjects.add("posthog-android-sample")
}
