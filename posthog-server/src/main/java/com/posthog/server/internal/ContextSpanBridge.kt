package com.posthog.server.internal

import com.posthog.server.PostHogRequestContext

private val openTelemetrySdkAvailable: Boolean by lazy {
    try {
        Class.forName("io.opentelemetry.sdk.trace.ReadWriteSpan", false, PostHogRequestContext::class.java.classLoader)
        true
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }
}

internal fun enrichCurrentSpanSafely() {
    if (!openTelemetrySdkAvailable) {
        return
    }
    try {
        enrichActiveOpenTelemetrySpan()
    } catch (_: Exception) {
    } catch (_: LinkageError) {
    }
}
