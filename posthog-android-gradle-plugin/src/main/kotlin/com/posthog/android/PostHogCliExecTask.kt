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

    @get:Input
    @get:Optional
    public abstract val postHogDotenvFile: Property<String>

    init {
        postHogExecutable.convention(POSTHOG_CLI_DEFAULT_EXECUTABLE)
    }

    override fun exec() {
        runPostHogCli(resolvePostHogCli())
    }

    // Probe against the task's environment (defaults to the process env but
    // respects a PATH configured on the task) so discovery and execution agree.
    protected fun taskEnvironment(): Map<String, String> = environment.mapValues { it.value.toString() }

    /** Finds the posthog-cli to run. [isPostHogCliInstalled] tells whether the result can run. */
    protected fun resolvePostHogCli(warnWhenMissing: Boolean = true): String =
        resolvePostHogCliExecutable(
            configured = postHogExecutable.get(),
            logger = logger,
            environment = taskEnvironment(),
            // Upload task registrations set workingDir(project.rootDir), which is android/
            // in a standard React Native project. The resolver also checks its parent.
            workingDirectory = workingDir,
            warnWhenMissing = warnWhenMissing,
        )

    /** Runs [resolved] with the arguments from [getArguments] and the credentials of this task. */
    protected fun runPostHogCli(resolved: String) {
        shimPath(resolved, taskEnvironment())?.let { (pathKey, pathValue) -> environment(pathKey, pathValue) }

        val commandLine =
            postHogCliCommandLine(resolved, computeCommandLineArgs()).also {
                logger.info("cli command line: $it")
            }
        executable = commandLine.first()
        args(commandLine.drop(1))

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

    /**
     * Runs [resolved] with [args] in the environment [runPostHogCli] uses, and returns its output,
     * or null when it exits with an error. For questions to the CLI, such as `--help`, that need no
     * credentials and send nothing to PostHog.
     */
    protected fun queryPostHogCli(
        resolved: String,
        args: List<String>,
    ): String? {
        val environment = taskEnvironment().toMutableMap()
        shimPath(resolved, environment)?.let { (pathKey, pathValue) -> environment[pathKey] = pathValue }
        val process =
            ProcessBuilder(postHogCliCommandLine(resolved, args))
                .directory(workingDir)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(environment)
                }.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return output.takeIf { process.waitFor() == 0 }
    }

    // npm installs posthog-cli as a node shim, so the PATH gets the discovered bin dir prepended,
    // and the shim's `env node` resolves alongside it.
    private fun shimPath(
        resolved: String,
        taskEnvironment: Map<String, String>,
    ): Pair<String, String>? {
        if (resolved == postHogExecutable.get()) {
            return null
        }
        val executables = listOfNotNull(resolved, resolveNodeExecutable(taskEnvironment))
        return prependExecutableDirectoriesToPath(executables, taskEnvironment)
    }

    protected abstract fun getArguments(args: MutableList<String>)

    /** Computes the full list of arguments for the task */
    private fun computeCommandLineArgs(): List<String> {
        val args = mutableListOf<String>()
        getArguments(args)
        return args
    }
}

internal fun postHogCliCommandLine(
    executable: String,
    args: List<String>,
    isWindows: Boolean = Os.isFamily(Os.FAMILY_WINDOWS),
): List<String> {
    // On Windows a bare name may be the npm posthog-cli.cmd shim, which only cmd can find and
    // run. An .exe runs directly, which also keeps cmd from mangling a quoted path with spaces.
    if (isWindows && !executable.endsWith(".exe", ignoreCase = true)) {
        return listOf("cmd", "/c", executable) + args
    }
    return listOf(executable) + args
}
