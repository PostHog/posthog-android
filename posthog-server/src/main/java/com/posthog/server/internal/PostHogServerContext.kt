package com.posthog.server.internal

import com.posthog.internal.PostHogContext
import com.posthog.server.PostHogRequestContext

/**
 * PostHog context implementation for server-side SDK
 * Provides SDK identification in event properties
 */
internal class PostHogServerContext(private val config: com.posthog.PostHogConfig) : PostHogContext {
    override fun getStaticContext(): Map<String, Any> = emptyMap()

    override fun getDynamicContext(): Map<String, Any> {
        val requestContext = PostHogRequestContext.current() ?: return emptyMap()
        val properties = requestContext.properties?.toMutableMap() ?: mutableMapOf()
        val sessionId = requestContext.sessionId
        if (!sessionId.isNullOrBlank()) {
            properties.putIfAbsent("\$session_id", sessionId)
        }
        return properties
    }

    override fun getSdkInfo(): Map<String, Any> =
        mapOf(
            "\$lib" to config.sdkName,
            "\$lib_version" to config.sdkVersion,
        )
}
