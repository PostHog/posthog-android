package com.posthog.jvm

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property

/**
 * Build configuration for the `com.posthog.jvm` plugin, applied as:
 *
 * ```kotlin
 * posthog {
 *     requireUpload.set(true)
 * }
 * ```
 *
 * Each value also reads a Gradle property, for example `-Pposthog.requireUpload=true`, when the
 * build script does not set it.
 */
public abstract class PostHogJvmPluginExtension {
    /**
     * Skips the source upload. The build then ships no `posthog-meta.properties`, so its
     * exceptions show no source code. Gradle property: `posthog.skip`. Off by default.
     *
     * Use this instead of excluding the upload task (`-x`): the task also deletes the file an
     * earlier build left behind, so the app never reports the id of other code.
     */
    public abstract val skip: Property<Boolean>

    /**
     * Fails the build when posthog-cli is missing or has no jvm upload command, or PostHog
     * credentials are missing. By default the upload is skipped with a warning instead, so that
     * builds on machines without PostHog set up keep working. Set it in CI, so that a missing token
     * fails the build instead of shipping a build whose exceptions show no source code. A failed
     * upload fails the build either way.
     * Gradle property: `posthog.requireUpload`. Off by default.
     */
    public abstract val requireUpload: Property<Boolean>

    /** The release the upload belongs to. Defaults to the project name. */
    public abstract val releaseName: Property<String>

    /**
     * The version of that release. When unset, posthog-cli uses the current git commit, from the
     * repository or from the CI environment, so every deployed commit is its own release. A build
     * with no git commit and no version uploads its sources without a release.
     * Gradle property: `posthog.releaseVersion`.
     */
    public abstract val releaseVersion: Property<String>

    /**
     * Path to posthog-cli. When unset, the plugin looks on PATH and in the common install
     * locations. Gradle property: `posthog.cliExecutable`.
     */
    public abstract val cliExecutable: Property<String>

    /**
     * More source directories to upload with the `main` source set, for example those of the
     * other modules of a multi-module build. The SDK sends one id per application, so code from
     * another module shows source code only when its sources are part of this upload.
     */
    public abstract val additionalSourceDirs: ConfigurableFileCollection
}
