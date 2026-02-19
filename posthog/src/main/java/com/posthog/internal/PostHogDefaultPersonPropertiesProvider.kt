package com.posthog.internal

import com.posthog.PostHogInternal

/**
 * Provider for default person properties used in feature flag evaluation.
 */
@PostHogInternal
public fun interface PostHogDefaultPersonPropertiesProvider {
    public fun getDefaultPersonProperties(): Map<String, Any>
}
