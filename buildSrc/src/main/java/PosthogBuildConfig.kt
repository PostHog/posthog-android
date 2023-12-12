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
        val MIN_SDK = 21 // Session Replay (addOnFrameMetricsAvailableListener requires API 26)
        val TARGET_SDK = COMPILE_SDK
    }

    object Kotlin {
        // compiler has to match kotlin version
        // https://developer.android.com/jetpack/androidx/releases/compose-kotlin
        val COMPILER_EXTENSION_VERSION = "1.4.8" // kotlin 1.8.22

        val KOTLIN_COMPATIBILITY = "1.6"

        // also update buildSrc/gradle.kts - kotlinVersion
        val KOTLIN = "1.8.22"
    }

    object Plugins {
        val ANIMAL_SNIFFER = "1.7.1"
        val ANIMAL_SNIFFER_SDK_VERSION = "5.0.1_r2" // 21
        val SPOTLESS = "6.21.0"
        val DETEKT = "1.23.1"
        val API_VALIDATOR = "0.13.2"
        val BUILD_CONFIG = "4.1.2"
        val GUMMY_BEARS_API = "0.6.1"
        val SIGNATURE_JAVA18 = "1.0"
    }

    object Dependencies {
        // runtime
        val LIFECYCLE = "2.6.2"
        val GSON = "2.10.1"
        val OKHTTP = "4.11.0"
        val CURTAINS = "1.2.4"

        // tests
        val ANDROIDX_JUNIT = "1.1.5"
        val ANDROIDX_RUNNER = "1.5.2"
        val ANDROIDX_CORE = "1.5.0"
        val MOCKITO = "4.1.0" // mockito 5x requires Java 11 bytecode
        val MOCKITO_INLINE = "4.11.0" // mockito-inline 5x requires Java 11 bytecode
        val ROBOLECTRIC = "4.10.3"
    }
}
