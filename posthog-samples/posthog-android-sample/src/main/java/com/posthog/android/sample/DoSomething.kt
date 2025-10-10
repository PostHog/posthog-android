package com.posthog.android.sample

import com.posthog.PostHog

class DoSomething {
    fun doSomethingNow() {
        try {
            throw MyCustomException("Something went wrong")
        } catch (e: Throwable) {
            PostHog.captureException(e, mapOf("am-i-stupid" to true))
        }
    }
}
