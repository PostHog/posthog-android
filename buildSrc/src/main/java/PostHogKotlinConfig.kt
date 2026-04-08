import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

fun KotlinJvmCompilerOptions.postHogConfig(strict: Boolean = true) {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    val compatVersion = KotlinVersion.fromVersion(PosthogBuildConfig.Kotlin.KOTLIN_COMPATIBILITY)
    languageVersion.set(compatVersion)
    apiVersion.set(compatVersion)
    allWarningsAsErrors.set(true)
    if (strict) {
        // remove when https://youtrack.jetbrains.com/issue/KT-37652 is fixed
        freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}
