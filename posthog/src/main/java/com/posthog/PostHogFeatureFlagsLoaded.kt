package com.posthog

/**
 * The feature flags passed to a [PostHogFeatureFlagsCallback] callback.
 *
 * It holds the enabled flags as they were when the callback was invoked, and whether the latest
 * attempt to load them failed. Disabled flags are left out.
 *
 * Example:
 * ```kotlin
 * PostHog.reloadFeatureFlags { loaded ->
 *     if (loaded.errorsLoading) {
 *         // The values are the last known (cached) flags
 *     }
 *     if (loaded.variants["new-checkout"] == "test") {
 *         // Show the test variant
 *     }
 * }
 * ```
 *
 * @param featureFlags the flag values, keyed by flag key; disabled flags and non-flag values are dropped
 * @property errorsLoading `true` if the latest attempt to load feature flags failed. When it did,
 * [flags] and [variants] hold the last known values, which can be empty. It's also `true`, with no
 * values, when the SDK isn't set up or is disabled and `reloadFeatureFlags` is called.
 */
public class PostHogFeatureFlagsLoaded
    @PostHogInternal
    public constructor(
        featureFlags: Map<String, Any?>,
        public val errorsLoading: Boolean,
    ) {
        /**
         * The values of the enabled feature flags, keyed by flag key.
         *
         * A value is `true` for an enabled boolean flag, or the variant `String` for a multivariate flag.
         * Disabled flags are not included.
         */
        public val variants: Map<String, Any> =
            featureFlags.entries
                .mapNotNull { (key, value) -> if (value == true || value is String) key to value else null }
                .toMap()

        /**
         * The keys of the enabled feature flags.
         */
        public val flags: List<String> = variants.keys.toList()
    }
