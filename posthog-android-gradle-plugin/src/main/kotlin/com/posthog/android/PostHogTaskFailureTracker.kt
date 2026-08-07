package com.posthog.android

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Records the paths of failed tasks as the build runs, so upload tasks can
 * skip when the build they finalize failed. A build service rather than a
 * captured [org.gradle.api.Task] reference, because execution-time predicates
 * that resolve tasks break the configuration cache.
 */
internal abstract class PostHogTaskFailureTracker :
    BuildService<BuildServiceParameters.None>, OperationCompletionListener {
    private val failedTaskPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun onFinish(event: FinishEvent) {
        if (event is TaskFinishEvent && event.result is TaskFailureResult) {
            failedTaskPaths.add(event.descriptor.taskPath)
        }
    }

    fun anyFailed(taskPaths: Collection<String>): Boolean = taskPaths.any { it in failedTaskPaths }

    internal companion object {
        const val NAME: String = "postHogTaskFailureTracker"
    }
}
