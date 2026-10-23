package com.posthog

/**
 * Callback invoked when a feature flags load finishes, whether it succeeded or failed.
 *
 * It's used by `PostHogConfig.onFeatureFlags`, `reloadFeatureFlags` and `onFeatureFlags`. It runs on a background
 * thread, or right away on the calling thread when no load is attempted (for example, the SDK is
 * disabled). With bootstrapped flags, `PostHogConfig.onFeatureFlags` also runs once on the thread
 * calling `setup()`, before the first load finishes. Hop to the main thread before touching UI.
 *
 * Example:
 * ```kotlin
 * config.onFeatureFlags = PostHogFeatureFlagsCallback { loaded ->
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
