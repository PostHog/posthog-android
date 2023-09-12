import com.diffplug.spotless.LineEnding

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // android
    id("com.android.application") version "8.1.1" apply false
    id("com.android.library") version "8.1.1" apply false
    kotlin("android") version "1.8.10" apply false

    // jvm
    kotlin("jvm") version "1.8.10" apply false

    // plugins
    id("com.diffplug.spotless") version "6.21.0" apply true
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
