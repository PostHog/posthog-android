// adapted from https://github.com/getsentry/sentry-android-gradle-plugin/blob/0ce926822756c8379e281bed8c33237a400c9582/plugin-build/src/main/kotlin/io/sentry/android/gradle/tasks/SentryCliExecTask.kt#L13

package com.posthog.android

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.tasks.Exec
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "abstract task, should not be used directly")
abstract class PostHogCliExecTask : Exec() {
    override fun exec() {
        computeCommandLineArgs().let {
            commandLine(it)
            logger.info("cli args: $it")
        }
        super.exec()
    }

    abstract fun getArguments(args: MutableList<String>)

    /** Computes the full list of arguments for the task */
    private fun computeCommandLineArgs(): List<String> {
        val args = mutableListOf<String>()
        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
            args.add(0, "cmd")
            args.add(1, "/c")
        }

        // TODO: CLI path config
        args.add("posthog-cli")

        getArguments(args)

        return args
    }
}
