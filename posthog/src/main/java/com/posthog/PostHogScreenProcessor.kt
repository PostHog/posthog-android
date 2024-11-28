package com.posthog

public class PostHogScreenProcessor : PostHogPropertiesProcessor {
    override fun process(properties: MutableMap<String, Any>): Map<String, Any> {
        if (properties.containsKey("\$screen_name")) {
            return properties
        }

        val currentScreen = ScreenTracker.getCurrentScreenName()

        properties["\$screen_name"] = currentScreen

        return properties
    }
}