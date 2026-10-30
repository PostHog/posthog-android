package com.posthog

/**
 * Callback invoked when a feature flags load finishes, whether it succeeded or failed.
 *
 * It's used by `onFeatureFlags` and `reloadFeatureFlags`.
 *
 * For `onFeatureFlags`, it runs on the main thread on Android, and on the SDK's background remote
 * config thread on the JVM.
 *
 * For `reloadFeatureFlags`, it runs on a background thread, or right away on the calling thread when
 * no load is attempted (for example, the SDK is disabled). Hop to the main thread before touching UI.
 *
 * Example:
 * ```kotlin
 * val subscription = PostHog.onFeatureFlags { loaded ->
 *     if (!loaded.errorsLoading && "new-checkout" in loaded.flags) {
 *         // The flag is enabled
 *     }
 * }
 * ```
 */
public fun interface PostHogFeatureFlagsCallback {
    /**
     * Called when feature flags are loaded, or loading them failed.
     *
     * @param result the enabled flags, their values, and whether loading failed
     */
    public fun loaded(result: PostHogFeatureFlagsLoaded)
}
