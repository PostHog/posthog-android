package com.posthog

public fun interface PostHogBeforeSend {
    public fun run(event: PostHogEvent): PostHogEvent?
}
