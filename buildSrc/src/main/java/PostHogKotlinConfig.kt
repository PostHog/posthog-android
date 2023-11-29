import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions

fun KotlinJvmOptions.postHogConfig(strict: Boolean = true) {
    jvmTarget = PosthogBuildConfig.Build.JAVA_VERSION.toString()
    languageVersion = PosthogBuildConfig.Kotlin.KOTLIN_COMPATIBILITY
    allWarningsAsErrors = true
    apiVersion = PosthogBuildConfig.Kotlin.KOTLIN_COMPATIBILITY
    if (strict) {
        // remove when https://youtrack.jetbrains.com/issue/KT-37652 is fixed
        freeCompilerArgs += "-Xexplicit-api=strict"
    }
}
