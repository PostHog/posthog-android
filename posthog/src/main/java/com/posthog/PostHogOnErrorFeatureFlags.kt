package com.posthog

public fun interface PostHogOnErrorFeatureFlags {
    public fun error(e:Throwable)
}