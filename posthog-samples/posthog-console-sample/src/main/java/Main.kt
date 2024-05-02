package com.posthog

import java.lang.Thread.sleep

public fun main() {
    val config =
        PostHogConfig("phc_pQ70jJhZKHRvDIL5ruOErnPy6xiAiWCqlL4ayELj4X8").apply {
            debug = true
            flushAt = 1
            preloadFeatureFlags = false
        }
    PostHog.setup(config)

    PostHog.capture("Hello World!", distinctId = "123")

//    PostHog.flush()
//
//    PostHog.close()

    while (Thread.activeCount() > 1) {
        println("threads still active")
        sleep(10000)
    }
}
