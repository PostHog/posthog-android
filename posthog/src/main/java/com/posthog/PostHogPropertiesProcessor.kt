package com.posthog

public fun interface PostHogPropertiesProcessor {
    public fun process(properties: MutableMap<String, Any>): Map<String, Any>
}
