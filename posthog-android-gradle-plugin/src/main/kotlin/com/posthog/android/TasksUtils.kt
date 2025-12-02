// adapted from https://github.com/getsentry/sentry-android-gradle-plugin/blob/0ce926822756c8379e281bed8c33237a400c9582/plugin-build/src/main/kotlin/io/sentry/android/gradle/util/tasks.kt#L5

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
) {
    // we need to wait for project evaluation to have all tasks available, otherwise the new
    // AndroidComponentsExtension is configured too early to look up for the tasks
    project.afterEvaluate {
        val minifyTask = getMinifyTask(project, variantName)

        // we just hack ourselves into the Proguard/R8/DexGuard task's doLast.
        minifyTask?.configure {
            finalizedBy(this@hookWithMinifyTasks)
        }
    }
}

internal fun TaskProvider<out Task>.hookWithAssembleTasks(
    project: Project,
    variant: ApplicationVariant,
) {
    // we need to wait for project evaluation to have all tasks available, otherwise the new
    // AndroidComponentsExtension is configured too early to look up for the tasks
    project.afterEvaluate {
        val bundleTask =
            withLogging(project.logger, "bundleTask") { getBundleTask(project, variant.name) }
        getAssembleTaskProvider(project, variant)?.configure {
            finalizedBy(this@hookWithAssembleTasks)
        }
        getInstallTaskProvider(project, variant)?.configure {
            finalizedBy(this@hookWithAssembleTasks)
        }
        // if its a bundle aab, assemble might not be executed, so we hook into bundle task
        bundleTask?.configure {
            finalizedBy(this@hookWithAssembleTasks)
        }
    }
}

internal fun ApplicationVariant.mappingFileProvider(project: Project): Provider<FileCollection> =
    project.provider {
        project.files(artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
    }
