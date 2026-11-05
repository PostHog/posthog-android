package com.posthog

/**
 * A registration returned by `onFeatureFlags`.
 *
 * The callback stays registered until you call [unsubscribe]. You don't need to keep a reference
 * to this object unless you want to unsubscribe later.
 *
 * Example:
 * ```kotlin
 * val subscription = PostHog.onFeatureFlags { loaded ->
 *     // React to the new flags
 * }
 *
 * // Later, stop listening
 * subscription.unsubscribe()
 * ```
 */
public class PostHogFeatureFlagsSubscription
    @PostHogInternal
    public constructor(
        private val onUnsubscribe: () -> Unit,
    ) {
        /**
         * Stops the callback from being invoked. Calling it more than once has no effect.
         */
        public fun unsubscribe() {
            onUnsubscribe()
        }
    }
