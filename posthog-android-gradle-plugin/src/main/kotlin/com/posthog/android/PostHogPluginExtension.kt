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
     * Pass `--skip-on-conflict` to the native symbol and ProGuard mapping
     * uploads. A symbol set that already exists with different content is
     * left unchanged and the upload succeeds, instead of failing the build.
     * With [includeNativeSymbolSources], an existing native symbol set is
     * kept as is rather than replaced with one that bundles sources.
     *
     * Off by default. Mutually exclusive with [force]. A task's own
     * `skipOnConflict` property, when set, takes precedence.
     */
    public abstract val skipOnConflict: Property<Boolean>

    /**
     * Pass `--force` to the native symbol and ProGuard mapping uploads, so a
     * symbol set that already exists with different content is overwritten.
     *
     * Off by default. Mutually exclusive with [skipOnConflict]. A task's own
     * `force` property, when set, takes precedence.
     */
    public abstract val force: Property<Boolean>
}
