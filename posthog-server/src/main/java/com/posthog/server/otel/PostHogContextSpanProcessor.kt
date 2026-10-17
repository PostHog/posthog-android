package com.posthog.server.otel

import com.posthog.server.PostHogRequestContext
import com.posthog.server.internal.enrichSpanWithRequestContext
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor

/**
 * Adds available PostHog request IDs when an application span starts.
 *
 * Register this processor on the application's existing OpenTelemetry SDK provider.
 * OpenTelemetry SDK 1.32.0 or later must be supplied by the application. This processor
 * does not export spans or change providers, exporters, or sampling.
 *
 * The processor reads [PostHogRequestContext] on the current thread and snapshots
 * `sessionId` and `posthogDistinctId`. Existing attributes with either name take
 * precedence regardless of their type. Missing or blank IDs remain absent.
 *
 * Start and close a fresh PostHog context scope for each request. Work on another
 * thread requires its own explicit context scope. Close each scope on the thread
 * that opened it. This processor does not add
 * coroutine, Reactor, or other framework context propagation.
 */
public class PostHogContextSpanProcessor public constructor() : SpanProcessor {
    @Volatile private var stopped: Boolean = false

    override fun onStart(
        parentContext: Context,
        span: ReadWriteSpan,
    ) {
        if (!stopped) {
            enrichSpanWithRequestContext(span)
        }
    }

    override fun isStartRequired(): Boolean = true

    override fun onEnd(span: ReadableSpan): Unit = Unit

    override fun isEndRequired(): Boolean = false

    override fun shutdown(): CompletableResultCode {
        stopped = true
        return CompletableResultCode.ofSuccess()
    }

    override fun forceFlush(): CompletableResultCode = CompletableResultCode.ofSuccess()
}
