package com.posthog.android

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

class PostHogAndroidGradlePlugin : Plugin<Project> {
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

//                val tasksGeneratingProperties = mutableListOf<TaskProvider<out PropertiesFileOutputTask>>()

                // TODO: skip variants, skip autoUpload, release info, allow failure, debug mode

                val paths = OutputPaths(project, variant.name)
                val generateMapIdTask = generateMapIdTask(project, variant, paths)

                variant.apply {
                }
            }
        }
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
