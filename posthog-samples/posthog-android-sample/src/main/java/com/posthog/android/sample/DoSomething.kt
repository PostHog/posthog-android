package com.posthog.android.sample

import com.posthog.PostHog

class DoSomething {
    fun doSomethingNow() {
        try {
//            val suppresed = Exception("suppressed")
//
//            val cause = Exception("case")
//            cause.addSuppressed(suppresed)
//
//            throw Exception("Something went wrong", cause)
            val cause1 = Exception("Exception cause 1")
            val cause2 = Exception("Exception cause 2,", cause1)
            throw Exception("Something went wrong", cause2)
        } catch (e: Throwable) {
            e.printStackTrace()
            PostHog.captureException(e, mapOf("am-i-stupid" to true))
        }
    }
}
