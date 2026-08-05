package com.posthog.android

import org.gradle.api.provider.Property

/**
 * Build configuration for the PostHog gradle plugin, applied as:
 *
 * ```kotlin
 * posthog {
 *     uploadNativeSymbols.set(true)
 * }
 * ```
 */
public abstract class PostHogPluginExtension {
    /**
     * Upload the variant's native (`.so`) debug symbols to PostHog after
     * `assemble`, `install`, and `bundle`, so native crash stack traces can be
     * symbolicated. Covers every source of native libraries in the merged
     * output: NDK builds, `jniLibs`, and libraries packaged by dependencies.
     *
     * Off by default. The `uploadPostHogNativeSymbols<Variant>` task can
     * always be invoked explicitly, regardless of this flag.
     */
    public abstract val uploadNativeSymbols: Property<Boolean>
}
