package com.posthog.server

public object NoTracingFixture {
    @JvmStatic
    public fun main(args: Array<String>) {
        check(runCatching { Class.forName("io.opentelemetry.api.trace.Span") }.isFailure)
        var captured = 0
        val config = PostHogConfig("example-key", host = "https://example.com")
        config.addBeforeSend { event ->
            check(event.event == "\$exception")
            check(event.properties?.containsKey("\$trace_id") != true)
            check(event.properties?.containsKey("\$span_id") != true)
            captured++
            null
        }
        val client = PostHog.with(config)
        try {
            client.captureException(IllegalStateException("Example failure"), "example-user")
            client.captureException(IllegalStateException("Example failure"), "example-user", PostHogCaptureOptions.builder().build())
            check(captured == 2)
        } finally {
            client.close()
        }
    }
}
