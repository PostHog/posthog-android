package com.posthog

public interface PostHogBeforeSend {
    public fun run(event: PostHogEvent): PostHogEvent?
}
