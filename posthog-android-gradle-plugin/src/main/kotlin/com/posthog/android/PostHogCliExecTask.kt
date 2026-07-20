// Portions of this file are derived from getsentry/sentry-android-gradle-plugin
// Copyright (c) 2020 Sentry
// Licensed under the MIT License: https://github.com/getsentry/sentry-android-gradle-plugin/blob/main/LICENSE

package com.posthog.android

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "abstract task, should not be used directly")
public abstract class PostHogCliExecTask : Exec() {
    @get:Input
    public abstract val postHogExecutable: Property<String>

    @get:Input
    @get:Optional
    public abstract val postHogHost: Property<String>

    @get:Input
    @get:Optional
    public abstract val postHogProjectId: Property<String>

    @get:Input
    @get:Optional
    public abstract val postHogApiKey: Property<String>

    init {
        postHogExecutable.convention(POSTHOG_CLI_DEFAULT_EXECUTABLE)
    }

    override fun exec() {
        executable = resolvePostHogCliExecutable(postHogExecutable.get(), logger)

        val args =
            computeCommandLineArgs().also {
                logger.info("cli args: $it")
            }
        args(args)

        // Setup environment variables for authentication etc
        postHogHost.orNull?.let {
            environment("POSTHOG_CLI_HOST", it)
        }
        postHogProjectId.orNull?.let {
            environment("POSTHOG_CLI_PROJECT_ID", it)
        }
        postHogApiKey.orNull?.let {
            environment("POSTHOG_CLI_API_KEY", it)
        }

        super.exec()
    }

    protected abstract fun getArguments(args: MutableList<String>)

    /** Computes the full list of arguments for the task */
    private fun computeCommandLineArgs(): List<String> {
        val args = mutableListOf<String>()
        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
            args.add(0, "cmd")
            args.add(1, "/c")
        }

        getArguments(args)

        return args
    }
}
