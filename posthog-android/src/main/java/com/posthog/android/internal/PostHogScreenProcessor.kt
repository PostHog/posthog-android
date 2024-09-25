package com.posthog.android.internal

import com.posthog.PostHogPropertiesProcessor

internal class PostHogScreenProcessor: PostHogPropertiesProcessor {
    override fun process(properties: MutableMap<String, Any>): Map<String, Any> {
        if (properties.containsKey("\$screen_name")) {
            return properties
        }
        // TODO: probably read the value from PostHogActivityLifecycleCallbackIntegration
        // or a singleton that sets the current screen
        properties["\$screen_name"] = "set the current screen"
        return properties
    }
}
