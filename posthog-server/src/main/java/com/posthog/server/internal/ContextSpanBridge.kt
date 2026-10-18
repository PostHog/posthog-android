package com.posthog.server.internal

import com.posthog.PostHogStateless
import com.posthog.server.PostHogRequestContext
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private val registeredProcessors = WeakHashMap<Any, WeakReference<PostHogStateless>>()

internal fun registerSpanContextOwner(owner: PostHogStateless): Any =
    Any().also { token ->
        synchronized(registeredProcessors) {
            registeredProcessors[token] = WeakReference(owner)
        }
    }

internal fun unregisterSpanContextOwner(token: Any) {
    synchronized(registeredProcessors) {
        registeredProcessors.remove(token)
    }
}

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
    val owner = PostHogRequestContext.owner() ?: return
    val registered = synchronized(registeredProcessors) { registeredProcessors.values.any { it.get() === owner } }
    if (!registered || !openTelemetrySdkAvailable) {
        return
    }
    try {
        enrichActiveOpenTelemetrySpan(owner)
    } catch (_: Exception) {
    } catch (_: LinkageError) {
    }
}
