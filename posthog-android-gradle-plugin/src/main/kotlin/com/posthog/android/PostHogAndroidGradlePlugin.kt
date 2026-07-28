// Portions of this file are derived from getsentry/sentry-android-gradle-plugin
// Copyright (c) 2020 Sentry
// Licensed under the MIT License: https://github.com/getsentry/sentry-android-gradle-plugin/blob/main/LICENSE

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
                // Native symbol upload is independent of minification: native
                // crashes need `.so` debug symbols whether or not the JVM side
                // is obfuscated.
                registerNativeSymbolsUpload(project, variant)

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

    private fun registerNativeSymbolsUpload(
        project: Project,
        variant: ApplicationVariant,
    ) {
        val primaryOutput = variant.outputs.firstOrNull()
        val uploadTask =
            PostHogUploadNativeSymbolsTask.register(
                project = project,
                // The unstripped libraries as built; AGP strips them for
                // packaging in a later task. The subdirectory layout varies
                // across AGP versions, so point at the variant root and let
                // the CLI scan recursively.
                nativeLibsDirectory =
                    project.layout.buildDirectory
                        .dir("intermediates/merged_native_libs/${variant.name}"),
                taskSuffix = variant.name.capitalizeUS(),
                releaseName = variant.applicationId,
                releaseVersion = primaryOutput?.versionName?.map { it.orEmpty() },
                build = primaryOutput?.versionCode,
            )

        project.afterEvaluate {
            PostHogTasksProvider.getMergeNativeLibsTask(project, variant.name)?.let { merge ->
                uploadTask.configure { dependsOn(merge) }
            }
            // Auto-upload alongside assemble/install/bundle only when the app
            // module builds native code itself. Apps that only bundle prebuilt
            // `.so` files from dependencies can run the task explicitly.
            if (variant.externalNativeBuild != null) {
                uploadTask.hookWithAssembleTasks(project, variant)
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

        generateMapIdTask.hookWithMinifyTasks(project, variant.name, generateMapIdTask)

        uploadMapIdTask.hookWithAssembleTasks(project, variant)

        return generateMapIdTask
    }

    private fun uploadMapIdTask(
        project: Project,
        generateMapIdTask: Provider<PostHogGenerateMapIdTask>,
        variant: ApplicationVariant,
        mappingFiles: Provider<FileCollection>,
    ): TaskProvider<PostHogUploadProguardMappingsTask> {
        val primaryOutput = variant.outputs.firstOrNull()
        val uploadMapIdTask =
            PostHogUploadProguardMappingsTask.register(
                project = project,
                generateMapIdTask = generateMapIdTask,
                mappingFiles = mappingFiles,
                taskSuffix = variant.name.capitalizeUS(),
                releaseName = variant.applicationId,
                releaseVersion = primaryOutput?.versionName?.map { it.orEmpty() },
                build = primaryOutput?.versionCode,
            )
        return uploadMapIdTask
    }
}
