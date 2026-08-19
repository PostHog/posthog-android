// Portions of this file are derived from getsentry/sentry-android-gradle-plugin
// Copyright (c) 2020 Sentry
// Licensed under the MIT License: https://github.com/getsentry/sentry-android-gradle-plugin/blob/main/LICENSE

package com.posthog.android

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationVariant
import com.posthog.android.PostHogTasksProvider.getAssembleTaskProvider
import com.posthog.android.PostHogTasksProvider.getBundleTask
import com.posthog.android.PostHogTasksProvider.getInstallTaskProvider
import com.posthog.android.PostHogTasksProvider.getMinifyTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

internal fun TaskProvider<out Task>.hookWithMinifyTasks(
    project: Project,
    variantName: String,
    generateMapIdTask: TaskProvider<PostHogGenerateMapIdTask>? = null,
) {
    // we need to wait for project evaluation to have all tasks available, otherwise the new
    // AndroidComponentsExtension is configured too early to look up for the tasks
    project.afterEvaluate {
        val minifyTask = getMinifyTask(project, variantName)

        minifyTask?.let { minify ->
            minify.configure {
                finalizedBy(this@hookWithMinifyTasks)
            }
            this@hookWithMinifyTasks.configure {
                dependsOn(minify)
            }
            generateMapIdTask?.configure {
                val mappingFiles =
                    minify.map { minifyTask ->
                        minifyTask.outputs.files.filter { it.name == "mapping.txt" }
                    }
                this.proguardMappingFiles.setFrom(mappingFiles)
            }
        }
    }
}

internal fun TaskProvider<out Task>.hookWithAssembleTasks(
    project: Project,
    variant: ApplicationVariant,
    failureTracker: Provider<PostHogTaskFailureTracker>,
) {
    // we need to wait for project evaluation to have all tasks available, otherwise the new
    // AndroidComponentsExtension is configured too early to look up for the tasks
    project.afterEvaluate {
        val bundleTask =
            withLogging(project.logger, "bundleTask") { getBundleTask(project, variant.name) }
        val anchors = mutableListOf<TaskProvider<out Task>>()
        getAssembleTaskProvider(project, variant)?.also { anchors.add(it) }?.configure {
            finalizedBy(this@hookWithAssembleTasks)
        }
        getInstallTaskProvider(project, variant)?.also { anchors.add(it) }?.configure {
            finalizedBy(this@hookWithAssembleTasks)
        }
        // if its a bundle aab, assemble might not be executed, so we hook into bundle task
        bundleTask?.also { anchors.add(it) }?.configure {
            finalizedBy(this@hookWithAssembleTasks)
        }
        // Finalizers run even when the build they finalize fails; skip the
        // upload then, so artifacts of a failed build are not uploaded and the
        // upload's own errors cannot obscure the original failure. The check
        // goes through a build service keyed by task path: resolving Task
        // instances inside an execution-time predicate breaks the
        // configuration cache. Explicit invocations are unaffected: an
        // unexecuted anchor never registers a failure.
        val anchorPaths = anchors.map { anchor -> taskPath(project, anchor.name) }
        this@hookWithAssembleTasks.configure {
            usesService(failureTracker)
            onlyIf("the finalized build succeeded") {
                !failureTracker.get().anyFailed(anchorPaths)
            }
        }
    }
}

private fun taskPath(
    project: Project,
    taskName: String,
): String = if (project.path == ":") ":$taskName" else "${project.path}:$taskName"

internal fun ApplicationVariant.mappingFileProvider(project: Project): Provider<FileCollection> =
    project.provider {
        project.files(artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
    }
