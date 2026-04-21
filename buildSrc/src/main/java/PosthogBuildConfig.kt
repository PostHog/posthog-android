import org.gradle.api.JavaVersion

object PosthogBuildConfig {
    // shared versions — loaded from the repository gradle.properties (single source of truth)
    private val versions =
        java.util.Properties().apply {
            findGradlePropertiesFile().inputStream().use { load(it) }
        }

    private fun findGradlePropertiesFile(): java.io.File {
        val start = java.io.File(System.getProperty("user.dir")).absoluteFile
        return generateSequence(start) { it.parentFile }
            .map { java.io.File(it, "gradle.properties") }
            .firstOrNull { it.isFile }
            ?: error("Could not find gradle.properties starting from ${start.absolutePath}")
    }

    fun shouldSkipDebugVariant(name: String): Boolean {
        return isCI() && name == "debug"
    }

    fun isCI(): Boolean {
        return System.getenv("CI")?.toBoolean() ?: false
    }

    object Build {
        // bytecode target for library consumers
        val JAVA_VERSION = JavaVersion.VERSION_1_8

        // JDK toolchain version for compilation (AGP 8.9+ requires JDK 17)
        val JDK_VERSION = (versions["jdkVersion"] as String).toInt()
    }

    object Android {
        val COMPILE_SDK = 36

        // when changing this, remember to check the ANIMAL_SNIFFER_SDK_VERSION
        // Session Replay (addOnFrameMetricsAvailableListener requires API 26)
        val MIN_SDK = 23
        val TARGET_SDK = COMPILE_SDK
    }

    object Kotlin {
        // languageVersion and apiVersion for backward compatibility with consumers
        val KOTLIN_COMPATIBILITY = versions["kotlinCompatibility"] as String

        val KOTLIN = versions["kotlinVersion"] as String
    }

    object Plugins {
        val ANIMAL_SNIFFER = "1.7.2"
        val ANIMAL_SNIFFER_SDK_VERSION = "6.0_r3" // API 23
        val ANIMAL_SNIFFER_SDK_ANNOTATION = "1.23"
        val SPOTLESS = "6.25.0"
        val DETEKT = "1.23.6"
        val API_VALIDATOR = "0.16.3"
        val BUILD_CONFIG = "5.5.1"
        val GUMMY_BEARS_API = "0.8.0"
        val SIGNATURE_JAVA18 = "1.0"
    }

    object Dependencies {
        // runtime
        val LIFECYCLE = "2.6.2"
        val GSON = "2.10.1"

        val OKHTTP = "4.12.0"
        val CURTAINS = "1.2.5"
        val ANDROIDX_CORE = "1.5.0"
        val ANDROIDX_COMPOSE = "1.0.0"
        val ANDROIDX_ACTIVITY = "1.7.2"
        val FIREBASE_MESSAGING = "24.1.0"

        // tests
        val ANDROIDX_JUNIT = "1.2.1"
        val ANDROIDX_RUNNER = "1.6.2"
        val MOCKITO = "4.1.0" // mockito 5x requires Java 11 bytecode
        val MOCKITO_INLINE = "4.11.0" // mockito-inline 5x requires Java 11 bytecode
        val ROBOLECTRIC = "4.14.1"
    }
}
