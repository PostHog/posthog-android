package com.posthog.android.internal

import com.posthog.PostHogPropertiesProcessor

internal class PostHogScreenProcessor : PostHogPropertiesProcessor {
    override fun process(properties: MutableMap<String, Any>): Map<String, Any> {
        if (properties.containsKey("\$screen_name")) {
            return properties
        }

        val currentScreen = ScreenTracker.getCurrentScreenName()

        properties["\$screen_name"] = currentScreen

        return properties
    }
}
