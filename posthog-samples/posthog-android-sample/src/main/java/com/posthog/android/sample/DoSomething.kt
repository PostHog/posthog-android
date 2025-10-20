package com.posthog.android.sample

import com.posthog.PostHog

class DoSomething {
    fun doSomethingNow() {
        try {
            throw MyCustomException("Something went wrong")
        } catch (e: Throwable) {
            PostHog.captureException(e, mapOf("my-custom-error" to true))
        }
    }
}
