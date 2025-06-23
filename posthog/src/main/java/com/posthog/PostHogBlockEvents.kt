package com.posthog

public interface PostHogBlockEvents {
    public fun checkBlockEvent(event: PostHogEvent): PostHogEvent?
}