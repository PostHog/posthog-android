package com.posthog.server.internal

import com.posthog.server.PostHogRequestContext
import io.opentelemetry.api.trace.Span
import io.opentelemetry.sdk.trace.ReadWriteSpan

internal fun enrichActiveOpenTelemetrySpan() {
    val span = Span.current()
    if (span is ReadWriteSpan) {
        enrichSpanWithRequestContext(span)
    }
}

internal fun enrichSpanWithRequestContext(span: ReadWriteSpan) {
    try {
        if (!span.isRecording) {
            return
        }
        val context = PostHogRequestContext.current() ?: return
        val existingNames = span.toSpanData().attributes.asMap().keys.map { it.key }.toSet()
        listOf("sessionId" to context.sessionId, "posthogDistinctId" to context.distinctId).forEach { (key, value) ->
            if (!value.isNullOrBlank() && key !in existingNames) {
                span.setAttribute(key, value)
            }
        }
    } catch (_: Exception) {
    } catch (_: LinkageError) {
    }
}
