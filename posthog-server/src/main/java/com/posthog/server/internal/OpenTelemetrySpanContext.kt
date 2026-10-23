package com.posthog.server.internal

import com.posthog.server.PostHog
import com.posthog.server.PostHogInterface
import com.posthog.server.PostHogRequestContext
import io.opentelemetry.api.trace.Span
import io.opentelemetry.sdk.trace.ReadWriteSpan

internal fun enrichActiveOpenTelemetrySpan(owner: PostHogInterface) {
    val span = Span.current()
    if (span is ReadWriteSpan) {
        enrichSpanWithRequestContext(owner, span)
    }
}

private fun PostHogInterface.optedOut(): Boolean =
    when (this) {
        is PostHog -> isOptOut()
    }

internal fun enrichSpanWithRequestContext(
    owner: PostHogInterface,
    span: ReadWriteSpan,
) {
    try {
        // Check the owner first because a closed client logs on each isOptOut() call.
        if (PostHogRequestContext.owner() !== owner || !span.isRecording || owner.optedOut()) {
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
