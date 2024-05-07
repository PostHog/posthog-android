package com.posthog

import java.lang.Thread.sleep

public fun main() {
    val config =
        PostHogConfig("phc_pQ70jJhZKHRvDIL5ruOErnPy6xiAiWCqlL4ayELj4X8").apply {
            debug = true
            flushAt = 1
        }
    PostHog.setup(config)

    PostHog.capture(
        event = "Hello World!",
        distinctId = "123",
        properties = mapOf("test" to true),
        userProperties = mapOf("name" to "my name"),
        userPropertiesSetOnce = mapOf("age" to 33),
        groups = mapOf("company" to "posthog"),
    )
    PostHog.isFeatureEnabled(
        "myFlag",
        defaultValue = false,
        distinctId = "123",
        groups = mapOf("company" to "posthog"),
    )

    PostHog.flush()
//
    PostHog.close()

    while (Thread.activeCount() > 1) {
        println("threads still active")
        sleep(10000)
    }
}
