// Portions of this file are derived from getsentry/sentry-android-gradle-plugin
// Copyright (c) 2020 Sentry
// Licensed under the MIT License: https://github.com/getsentry/sentry-android-gradle-plugin/blob/main/LICENSE

package com.posthog.android

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault

/**
 * Uploads the variant's native (`.so`) debug symbols to PostHog via
 * `posthog-cli symbol-sets upload`, so native crash stack frames can be
 * symbolicated server-side.
 *
 * The upload reads the *merged* native libs intermediates — the unstripped
 * libraries as built, before AGP strips them for packaging. The CLI scans the
 * directory and uploads every library that carries debug info and a GNU build
 * id, reporting (but not failing on) pre-stripped libraries from dependencies.
 */
@DisableCachingByDefault(because = "Uploads should not be cached")
public abstract class PostHogUploadNativeSymbolsTask : PostHogCliExecTask() {
    init {
        description = "Uploads native (.so) debug symbols to PostHog"

        // Uploads have no outputs; without this the task would always rerun.
        outputs.upToDateWhen { true }
    }

    /**
     * Root of the variant's merged native libs intermediates. Not tracked as a
     * task input: uploads are gated by [outputs.upToDateWhen] and snapshotting
     * every `.so` would only slow the build.
     */
    @get:Internal
    public abstract val nativeLibsDirectory: DirectoryProperty

    @get:Input
    @get:Optional
    public abstract val releaseName: Property<String>

    @get:Input
    @get:Optional
    public abstract val releaseVersion: Property<String>

    @get:Input
    @get:Optional
    public abstract val build: Property<Int?>

    override fun getArguments(args: MutableList<String>) {
        args.add("symbol-sets")
        args.add("upload")
        args.add("--directory")
        args.add(nativeLibsDirectory.get().asFile.toString())
        releaseName.orNull?.takeIf { it.isNotEmpty() }?.let {
            args.add("--release-name")
            args.add(it)
        }
        releaseVersion.orNull?.takeIf { it.isNotEmpty() }?.let {
            args.add("--release-version")
            args.add(it)
        }
        build.orNull?.takeIf { it > 0 }?.let {
            args.add("--build")
            args.add(it.toString())
        }
    }

    internal companion object {
        fun register(
            project: Project,
            nativeLibsDirectory: Provider<org.gradle.api.file.Directory>,
            taskSuffix: String = "",
            releaseName: Provider<String>? = null,
            releaseVersion: Provider<String>? = null,
            build: Provider<Int?>? = null,
        ): TaskProvider<PostHogUploadNativeSymbolsTask> {
            return project.tasks.register(
                "uploadPostHogNativeSymbols$taskSuffix",
                PostHogUploadNativeSymbolsTask::class.java,
            ) {
                workingDir(project.rootDir)
                this.nativeLibsDirectory.set(nativeLibsDirectory)
                releaseName?.let { this.releaseName.set(it) }
                releaseVersion?.let { this.releaseVersion.set(it) }
                build?.let { this.build.set(it) }
                resolvePostHogDotenvFile(project)?.let { this.postHogDotenvFile.set(it) }
                onlyIf("the variant has native libraries") {
                    val dir = (it as PostHogUploadNativeSymbolsTask).nativeLibsDirectory.get().asFile
                    dir.walkTopDown().any { file -> file.extension == "so" }
                }
            }
        }
    }
}
