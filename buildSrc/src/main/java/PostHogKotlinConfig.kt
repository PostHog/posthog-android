import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions

fun KotlinJvmOptions.postHogConfig() {
    jvmTarget = PosthogBuildConfig.Build.JAVA_VERSION.toString()
    languageVersion = PosthogBuildConfig.Kotlin.KOTLIN_COMPATIBILITY
    allWarningsAsErrors = true
    apiVersion = PosthogBuildConfig.Kotlin.KOTLIN_COMPATIBILITY
}
