package com.posthog.internal.errortracking

internal class PostHogThrowable(throwable: Throwable, val thread: Thread = Thread.currentThread()) : Throwable(throwable) {
    val handled: Boolean = false
    val isFatal: Boolean = true
    val mechanism: String = "UncaughtExceptionHandler"
}
