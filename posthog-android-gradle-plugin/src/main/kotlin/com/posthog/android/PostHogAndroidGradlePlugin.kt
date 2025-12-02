// adapted from https://github.com/getsentry/sentry-android-gradle-plugin/blob/0ce926822756c8379e281bed8c33237a400c9582/plugin-build/src/main/kotlin/io/sentry/android/gradle/SentryPlugin.kt#L9

package com.posthog.android

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

internal class PostHogAndroidGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (!project.plugins.hasPlugin("com.android.application")) {
            project.logger.warn(
                """
                Using 'com.posthog.android' is only supported for the app module.
                Please make sure that you apply the PostHog gradle plugin alongside 'com.android.application' on the _module_ level, and not on the root project level.
                """
                    .trimIndent(),
            )
        }

        project.pluginManager.withPlugin("com.android.application") {
            val androidComponentsExt =
                project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

            androidComponentsExt.onVariants { variant ->
                if (!variant.isMinifyEnabled) {
                    return@onVariants
                }

                val tasksGeneratingProperties = mutableListOf<TaskProvider<out PropertiesFileOutputTask>>()

                // TODO: skip variants, skip autoUpload, release info, allow failure, debug mode

                val paths = OutputPaths(project, variant.name)
                val generateMapIdTask = generateMapIdTask(project, variant, paths)
                tasksGeneratingProperties.add(generateMapIdTask)

                variant.apply {
                    val injectAssetsTask =
                        InjectPostHogMetaPropertiesIntoAssetsTask.register(
                            project = project,
                            tasksGeneratingProperties = tasksGeneratingProperties,
                            taskSuffix = variant.name,
                        )

                    assetsWiredWithDirectories(
                        variant = variant,
                        task = injectAssetsTask,
                        inputDir = InjectPostHogMetaPropertiesIntoAssetsTask::inputDir,
                        outputDir = InjectPostHogMetaPropertiesIntoAssetsTask::outputDir,
                    )

                    // TODO: flutter doesn't use the transform API, and manually wires up task dependencies
                }
            }
        }
    }

    private fun <T : Task> assetsWiredWithDirectories(
        variant: ApplicationVariant,
        task: TaskProvider<T>,
        inputDir: (T) -> DirectoryProperty,
        outputDir: (T) -> DirectoryProperty,
    ) {
        variant.artifacts
            .use(task)
            .wiredWithDirectories(inputDir, outputDir)
            .toTransform(SingleArtifact.ASSETS)
    }

    private fun generateMapIdTask(
        project: Project,
        variant: ApplicationVariant,
        paths: OutputPaths,
    ): TaskProvider<PostHogGenerateMapIdTask> {
        val generateMapIdTask =
            PostHogGenerateMapIdTask.register(
                project = project,
                proguardMappingFile = variant.mappingFileProvider(project),
                taskSuffix = variant.name.capitalizeUS(),
                output = paths.proguardMapIdDir,
            )

        val uploadMapIdTask =
            uploadMapIdTask(
                project = project,
                generateMapIdTask = generateMapIdTask,
                variant = variant,
                mappingFiles = variant.mappingFileProvider(project),
            )

        generateMapIdTask.hookWithMinifyTasks(project, variant.name)

        uploadMapIdTask.hookWithAssembleTasks(project, variant)

        return generateMapIdTask
    }

    private fun uploadMapIdTask(
        project: Project,
        generateMapIdTask: Provider<PostHogGenerateMapIdTask>,
        variant: ApplicationVariant,
        mappingFiles: Provider<FileCollection>,
    ): TaskProvider<PostHogUploadProguardMappingsTask> {
        val uploadMapIdTask =
            PostHogUploadProguardMappingsTask.register(
                project = project,
                generateMapIdTask = generateMapIdTask,
                mappingFiles = mappingFiles,
                taskSuffix = variant.name.capitalizeUS(),
            )
        return uploadMapIdTask
    }
}
