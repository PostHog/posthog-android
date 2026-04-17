// adapted from https://github.com/getsentry/sentry-android-gradle-plugin/blob/0ce926822756c8379e281bed8c33237a400c9582/plugin-build/src/main/kotlin/io/sentry/android/gradle/tasks/SentryCliExecTask.kt#L13

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
        postHogExecutable.convention("posthog-cli")
    }

    override fun exec() {
        executable = postHogExecutable.get()

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
