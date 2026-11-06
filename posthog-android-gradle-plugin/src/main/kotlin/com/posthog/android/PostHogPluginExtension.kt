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

    /**
     * Also bundle the project source files referenced by the debug info into
     * the native symbol upload (`posthog-cli symbol-sets upload
     * --include-source`), so resolved crash frames show source context.
     *
     * Off by default: source bundles grow the upload and ship your source
     * code to PostHog. Only takes effect together with [uploadNativeSymbols]
     * or an explicit task invocation.
     */
    public abstract val includeNativeSymbolSources: Property<Boolean>

    /**
     * Skip native symbol sets that already exist with different content instead of failing
     * the upload. Existing symbol sets are left unchanged. Off by default.
     *
     * Mutually exclusive with [force]. Only affects native symbol uploads.
     */
    public abstract val skipOnConflict: Property<Boolean>

    /**
     * Overwrite native symbol sets that already exist with different content instead of
     * failing the upload. Off by default. Including native sources already overwrites
     * existing symbols unless [skipOnConflict] is enabled.
     *
     * Mutually exclusive with [skipOnConflict]. Only affects native symbol uploads.
     */
    public abstract val force: Property<Boolean>
}
