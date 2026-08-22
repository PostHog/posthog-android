// Portions of this file are derived from getsentry/sentry-android-gradle-plugin
// Copyright (c) 2020 Sentry
// Licensed under the MIT License: https://github.com/getsentry/sentry-android-gradle-plugin/blob/main/LICENSE

package com.posthog.android

import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.work.DisableCachingByDefault
import java.io.File

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

    @get:Input
    @get:Optional
    public abstract val postHogDotenvFile: Property<String>

    init {
        postHogExecutable.convention(POSTHOG_CLI_DEFAULT_EXECUTABLE)
    }

    override fun exec() {
        val configured = postHogExecutable.get()
        // Probe against the task's environment (defaults to the process env but
        // respects a PATH configured on the task) so discovery and execution agree.
        val taskEnvironment = environment.mapValues { it.value.toString() }
        val resolved =
            resolvePostHogCliExecutable(
                configured = configured,
                logger = logger,
                environment = taskEnvironment,
                workingDirectory = workingDir,
            )
        if (resolved != configured) {
            // npm installs posthog-cli as a node shim; prepend the discovered
            // bin dir so the shim's `env node` resolves alongside it.
            val binDir = File(resolved).parent
            val path = taskEnvironment["PATH"].orEmpty()
            environment("PATH", "$binDir${File.pathSeparator}$path")
        }

        val args = mutableListOf<String>()
        getArguments(args)
        logger.info("cli args: $args")
        commandLine(buildPostHogCliCommandLine(resolved, args))

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
        postHogDotenvFile.orNull?.let {
            environment("POSTHOG_CLI_DOTENV_FILE", it)
        }

        super.exec()
    }

    protected abstract fun getArguments(args: MutableList<String>)
}
