package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public class RRPluginEvent(plugin: String, payload: Map<String, Any>, timestamp: Long) : RREvent(
    type = RREventType.Plugin,
    data =
        mapOf(
            "plugin" to plugin,
            "payload" to payload,
        ),
    timestamp = timestamp,
)
