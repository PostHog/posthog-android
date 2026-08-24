package com.posthog.internal.errortracking

internal class PostHogThrowable(throwable: Throwable, val thread: Thread = Thread.currentThread()) : Throwable(throwable) {
    val handled: Boolean = false
    val isFatal: Boolean = true
    // Canonical capture-boundary category from the sdk-specs exception-event-metadata spec; the
    // concrete hook goes into the event-level $exception_source, not in here.
    val mechanism: String = "onuncaughtexception"
}
