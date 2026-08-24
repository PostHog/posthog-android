package com.posthog.internal.errortracking

internal class PostHogThrowable(
    throwable: Throwable,
    val thread: Thread = Thread.currentThread(),
    // Whether the uncaught boundary is expected to terminate the process ($exception_level fatal)
    // or only the crashing thread ($exception_level error). Defaults to fatal: on Android any
    // uncaught exception kills the app; the server SDK supplies a per-thread policy.
    val isFatal: Boolean = true,
) : Throwable(throwable) {
    val handled: Boolean = false

    // Canonical capture-boundary category from the sdk-specs exception-event-metadata spec; the
    // concrete hook goes into the event-level $exception_source, not in here.
    val mechanism: String = "onuncaughtexception"
}
