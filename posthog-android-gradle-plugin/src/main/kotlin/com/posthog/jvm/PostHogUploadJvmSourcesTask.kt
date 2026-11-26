package com.posthog.jvm

import com.posthog.android.PostHogCliExecTask
import com.posthog.android.isPostHogCliInstalled
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.work.DisableCachingByDefault

/**
 * Uploads the application's sources with `posthog-cli jvm upload`, and writes the id they were
 * uploaded under into `posthog-meta.properties` in [outputDirectory]. The plugin adds that
 * directory to the `main` resources, so every archive of the application carries the id of the
 * sources it was built from. The PostHog server SDK reads the file at startup and sends the id on
 * every exception frame.
 */
@DisableCachingByDefault(because = "Uploads should not be cached")
public abstract class PostHogUploadJvmSourcesTask : PostHogCliExecTask() {
    /**
     * The directories to upload. Only Java and Kotlin files in them are uploaded.
     *
     * Internal, so that Gradle does not hash every source file a second time: posthog-cli
     * hashes them anyway, and the task runs on every build. The plugin adds a dependency on the
     * tasks that generate any of these directories.
     */
    @get:Internal
    public abstract val sourceDirectories: ConfigurableFileCollection

    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    @get:Input
    @get:Optional
    public abstract val releaseName: Property<String>

    @get:Input
    @get:Optional
    public abstract val releaseVersion: Property<String>

    @get:Input
    public abstract val requireUpload: Property<Boolean>

    @get:Input
    public abstract val skip: Property<Boolean>

    init {
        description = "Uploads the application's sources to PostHog, so exceptions show source code"
        requireUpload.convention(false)
        skip.convention(false)
        // The id depends on the release, and the default release version is the git commit,
        // which is no input of this task. Without credentials the task uploads nothing, and a
        // later build with credentials must still upload. posthog-cli checks with the server and
        // sends no source files when they are already there, so a run of unchanged code is cheap.
        outputs.upToDateWhen { false }
    }

    override fun exec() {
        val metaProperties = outputDirectory.file(POSTHOG_META_PROPERTIES_FILE).get().asFile
        // A file left by an earlier build would point this build's exceptions at that build's
        // sources, so it goes first, and a skipped or failed run leaves no id behind.
        if (metaProperties.exists() && !metaProperties.delete()) {
            error("[PostHog] Could not delete the stale $metaProperties")
        }

        if (skip.get()) {
            logger.lifecycle("[PostHog] Skipping the source upload (posthog.skip is set)")
            return
        }
        if (existingSourceDirectories().isEmpty()) {
            logger.info("[PostHog] Skipping the source upload: none of the source directories exist")
            return
        }

        val executable = resolvePostHogCli(warnWhenMissing = false)
        if (!isPostHogCliInstalled(executable, taskEnvironment())) {
            skipOrFail("[PostHog] posthog-cli was not found$INSTALL_HINT")
            return
        }
        // A posthog-cli from before `jvm upload` exits with an error on the unknown subcommand,
        // which would fail the build even when the upload is optional.
        val help = queryPostHogCli(executable, listOf("jvm", "upload", "--help"))
        if (help == null || !help.contains("--skip-without-credentials")) {
            skipOrFail("[PostHog] posthog-cli ($executable) is too old: it has no jvm upload command$UPDATE_HINT")
            return
        }

        runPostHogCli(executable)

        if (!metaProperties.isFile) {
            if (requireUpload.get()) {
                error("[PostHog] posthog-cli exited successfully but did not write $metaProperties")
            }
            // posthog-cli skips the upload when it finds no credentials, and logs why.
            logger.warn("[PostHog] The source upload was skipped. $SKIPPED_WARNING")
        }
    }

    override fun getArguments(args: MutableList<String>) {
        args.add("jvm")
        args.add("upload")
        existingSourceDirectories().forEach {
            args.add("--source")
            args.add(it.absolutePath)
        }
        args.add("--meta-out")
        args.add(outputDirectory.get().asFile.absolutePath)
        if (!requireUpload.get()) {
            args.add("--skip-without-credentials")
        }
        releaseName.orNull?.takeIf { it.isNotBlank() }?.let {
            args.add("--release-name")
            args.add(it)
        }
        releaseVersion.orNull?.takeIf { it.isNotBlank() }?.let {
            args.add("--release-version")
            args.add(it)
        }
    }

    private fun skipOrFail(message: String) {
        if (requireUpload.get()) {
            error(message)
        }
        logger.warn("$message $SKIPPED_WARNING")
    }

    private fun existingSourceDirectories() = sourceDirectories.files.filter { it.isDirectory }

    internal companion object {
        const val POSTHOG_META_PROPERTIES_FILE: String = "posthog-meta.properties"

        private const val INSTALL_HINT =
            ". Install it (npm install -g @posthog/cli) or set posthog.cliExecutable."

        private const val UPDATE_HINT =
            ". Update it (npm install -g @posthog/cli@latest or brew upgrade posthog-cli) or set " +
                "posthog.cliExecutable."

        private const val SKIPPED_WARNING =
            "Exceptions from this build show no source code. Set posthog.requireUpload to fail " +
                "the build instead."
    }
}
