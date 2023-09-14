import org.gradle.api.JavaVersion

object PosthogBuildConfig {
    fun shouldSkipDebugVariant(name: String): Boolean {
        return System.getenv("CI")?.toBoolean() ?: false && name == "debug"
    }

    object Build {
        val JAVA_VERSION = JavaVersion.VERSION_1_8
    }

    object Android {
        val COMPILE_SDK = 34
        val MIN_SDK = 21
        val TARGET_SDK = COMPILE_SDK
        val AGP = "8.1.1"
    }

    object Kotlin {
        // compiler has to match kotlin version
        // https://developer.android.com/jetpack/androidx/releases/compose-kotlin
        val COMPILER_EXTENSION_VERSION = "1.4.3" // kotlin 1.8.10

        // kotlin version has to match kotlinCompilerExtensionVersion
        val KOTLIN = "1.8.10"
        val KOTLIN_COMPATIBILITY = "1.5"
    }

    object Plugins {
        val SPOTLESS = "6.21.0"
        val DETEKT = "1.23.1"
        val API_VALIDATOR = "0.13.2"
    }
}
