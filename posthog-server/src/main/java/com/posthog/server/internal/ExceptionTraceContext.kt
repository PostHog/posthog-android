package com.posthog.server.internal

import com.posthog.server.PostHogRequestContext
import io.opentelemetry.api.trace.Span

internal fun withActiveTraceContext(properties: Map<String, Any>?): Map<String, Any>? {
    return try {
        val context = Span.current().spanContext
        if (!context.isValid) {
            return properties
        }
        val traceId = context.traceId
        val spanId = context.spanId
        if (!traceId.matches(Regex("[0-9a-f]{32}")) || traceId.all { it == '0' } ||
            !spanId.matches(Regex("[0-9a-f]{16}")) || spanId.all { it == '0' }
        ) {
            return properties
        }
        val requestProperties = PostHogRequestContext.current()?.properties.orEmpty()
        mapOf("\$trace_id" to traceId, "\$span_id" to spanId)
            .filterKeys { !requestProperties.containsKey(it) } + properties.orEmpty()
    } catch (_: Throwable) {
        properties
    }
}
