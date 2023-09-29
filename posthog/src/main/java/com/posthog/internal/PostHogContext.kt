package com.posthog.internal

import com.posthog.PostHogInternal

/**
 * An Interface that reads the static and dynamic context
 * For example, screen's metrics, app's name and version, device details, connectivity status
 */
@PostHogInternal
public interface PostHogContext {
    public fun getStaticContext(): Map<String, Any>

    public fun getDynamicContext(): Map<String, Any>
}
