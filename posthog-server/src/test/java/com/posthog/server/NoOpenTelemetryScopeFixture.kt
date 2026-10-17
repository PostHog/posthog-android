package com.posthog.server

public object NoOpenTelemetryScopeFixture {
    @JvmStatic
    public fun main(args: Array<String>) {
        check(runCatching { Class.forName("io.opentelemetry.sdk.trace.ReadWriteSpan") }.isFailure)
        val apiPresent = runCatching { Class.forName("io.opentelemetry.api.trace.Span") }.isSuccess
        check(apiPresent == (args.firstOrNull() == "api-only"))
        PostHogRequestContext.withContext(PostHogRequestContextData("example-person", "example-session"), fresh = true) {
            check(PostHogRequestContext.current()?.sessionId == "example-session")
            try {
                PostHogRequestContext.withContext(PostHogRequestContextData(sessionId = "nested")) {
                    throw IllegalStateException("synthetic failure")
                }
            } catch (_: IllegalStateException) {
                check(PostHogRequestContext.current()?.sessionId == "example-session")
            }
        }
        check(PostHogRequestContext.current() == null)
    }
}
