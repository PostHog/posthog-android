package com.posthog.internal

import com.posthog.PostHogInternal

@PostHogInternal
public interface PostHogContext {
    public fun getStaticContext(): Map<String, Any>

    public fun getDynamicContext(): Map<String, Any>
}
