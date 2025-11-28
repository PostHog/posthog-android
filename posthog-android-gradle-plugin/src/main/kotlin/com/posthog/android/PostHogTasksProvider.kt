// adapted from https://github.com/getsentry/sentry-android-gradle-plugin/blob/0ce926822756c8379e281bed8c33237a400c9582/plugin-build/src/main/kotlin/io/sentry/android/gradle/SentryTasksProvider.kt#L10

package com.posthog.android

import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.impl.VariantImpl
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.tasks.TaskProvider

internal object PostHogTasksProvider {
    /**
     * Returns the minify task for the given project and variant. It could be either ProGuard, R8 or
     * DexGuard.
     *
     * @return the task or null otherwise
     */
    @JvmStatic
    fun getMinifyTask(
        project: Project,
        variantName: String,
    ): TaskProvider<Task>? {
        val tasks =
            listOf(
                "minify${variantName.capitalized}WithR8",
                "minify${variantName.capitalized}WithProguard",
            )
        return project.findTask(tasks)
    }

    /**
     * Returns the pre bundle task for the given project and variant.
     *
     * @return the task or null otherwise
     */
    @JvmStatic
    fun getBundleTask(
        project: Project,
        variantName: String,
    ): TaskProvider<Task>? = project.findTask(listOf("bundle${variantName.capitalized}"))

    /**
     * Returns the assemble task provider
     *
     * @return the provider if found or null otherwise
     */
    @JvmStatic
    fun getAssembleTaskProvider(
        project: Project,
        variant: ApplicationVariant,
    ): TaskProvider<out Task>? = variant.assembleProvider() ?: project.findTask(listOf("assemble${variant.name.capitalized}"))

    /**
     * Returns the install task provider
     *
     * @return the provider if found or null otherwise
     */
    @JvmStatic
    fun getInstallTaskProvider(
        project: Project,
        variant: ApplicationVariant,
    ): TaskProvider<out Task>? = variant.installProvider() ?: project.findTask(listOf("install${variant.name.capitalized}"))

    /** @return the first task found in the list or null */
    private fun Project.findTask(taskName: List<String>): TaskProvider<Task>? =
        taskName.firstNotNullOfOrNull {
            try {
                project.tasks.named(it)
            } catch (e: UnknownTaskException) {
                null
            }
        }

    private val String.capitalized: String
        get() = this.capitalizeUS()
}

internal fun ApplicationVariant.assembleProvider(): TaskProvider<out Task>? {
    return (this as? VariantImpl<*>)?.taskContainer?.assembleTask
}

internal fun ApplicationVariant.installProvider(): TaskProvider<out Task>? {
    return (this as? VariantImpl<*>)?.taskContainer?.installTask
}
