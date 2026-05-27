package com.posthog.server

import java.util.UUID

/**
 * Request-scoped PostHog analytics context.
 *
 * Use [beginScope] at the start of a server request and close the returned scope when the
 * request completes. Captures made on the same thread while the scope is active inherit the
 * context distinct ID, session ID, and properties unless the capture provides explicit values.
 *
 * Context is stored in a [ThreadLocal]. Framework integrations should create and close a fresh
 * scope per request and use their runtime's async propagation support when work moves across
 * threads.
 */
public class PostHogRequestContext private constructor() {
    public companion object {
        public const val DISTINCT_ID_HEADER: String = "X-PostHog-Distinct-Id"
        public const val SESSION_ID_HEADER: String = "X-PostHog-Session-Id"

        private const val SESSION_ID_PROPERTY = "\$session_id"
        private const val PROCESS_PERSON_PROFILE_PROPERTY = "\$process_person_profile"
        private const val MAX_HEADER_VALUE_LENGTH = 1000

        private val currentContext = ThreadLocal<PostHogRequestContextData?>()

        /**
         * Returns the context active on the current thread, or null when no scope is active.
         */
        @JvmStatic
        public fun current(): PostHogRequestContextData? = currentContext.get()

        /**
         * Starts a request context scope and returns an [AutoCloseable] that restores the previous context.
         *
         * Nested scopes inherit the current context by default. Pass [fresh] to start from an empty context.
         */
        @JvmStatic
        public fun beginScope(data: PostHogRequestContextData): Scope = beginScope(data, fresh = false)

        /**
         * Starts a request context scope and returns an [AutoCloseable] that restores the previous context.
         */
        @JvmStatic
        public fun beginScope(
            data: PostHogRequestContextData,
            fresh: Boolean,
        ): Scope {
            val previous = currentContext.get()
            val base = if (fresh) null else previous
            currentContext.set(resolveContext(data, base))
            return Scope(previous)
        }

        /**
         * Runs [block] inside a request context scope and restores the previous context afterwards.
         */
        public fun <T> withContext(
            data: PostHogRequestContextData,
            block: () -> T,
        ): T = withContext(data, fresh = false, block)

        /**
         * Runs [block] inside a request context scope and restores the previous context afterwards.
         */
        public fun <T> withContext(
            data: PostHogRequestContextData,
            fresh: Boolean,
            block: () -> T,
        ): T {
            beginScope(data, fresh).use {
                return block()
            }
        }

        /**
         * Extracts PostHog tracing headers into context data.
         *
         * Header names are matched case-insensitively. Values are trimmed, control characters are
         * removed, empty values are ignored, and long values are capped. When [captureTracingHeaders]
         * is false, tracing headers are ignored but [properties] are still preserved.
         */
        @JvmStatic
        @JvmOverloads
        public fun fromHeaders(
            headers: Map<String, *>?,
            captureTracingHeaders: Boolean = true,
            properties: Map<String, Any>? = null,
        ): PostHogRequestContextData {
            return try {
                val distinctId =
                    if (captureTracingHeaders) {
                        sanitizeHeaderValue(firstHeaderValue(headers, DISTINCT_ID_HEADER))
                    } else {
                        null
                    }
                val sessionId =
                    if (captureTracingHeaders) {
                        sanitizeHeaderValue(firstHeaderValue(headers, SESSION_ID_HEADER))
                    } else {
                        null
                    }

                val mergedProperties = properties?.toMutableMap() ?: mutableMapOf()
                if (!sessionId.isNullOrEmpty()) {
                    mergedProperties.putIfAbsent(SESSION_ID_PROPERTY, sessionId)
                }

                PostHogRequestContextData(
                    distinctId = distinctId,
                    sessionId = sessionId,
                    properties = mergedProperties.ifEmpty { null },
                )
            } catch (_: Exception) {
                PostHogRequestContextData(properties = properties?.ifEmpty { null })
            }
        }

        /**
         * Sanitizes an untrusted tracing header value.
         */
        internal fun sanitizeHeaderValue(value: String?): String? {
            if (value.isNullOrBlank()) {
                return null
            }

            val sanitized =
                buildString(value.length) {
                    value.forEach { character ->
                        if (!character.isISOControl()) {
                            append(character)
                        }
                    }
                }.trim()

            if (sanitized.isEmpty()) {
                return null
            }

            return if (sanitized.length <= MAX_HEADER_VALUE_LENGTH) {
                sanitized
            } else {
                sanitized.substring(0, MAX_HEADER_VALUE_LENGTH)
            }
        }

        internal fun resolveDistinctId(preferredDistinctId: String? = null): String? {
            if (!preferredDistinctId.isNullOrBlank()) {
                return preferredDistinctId
            }
            return current()?.distinctId?.takeUnless { it.isBlank() }
        }

        internal fun resolveCaptureContext(
            distinctId: String?,
            properties: Map<String, Any>?,
        ): PostHogResolvedCaptureContext {
            val resolvedDistinctId = resolveDistinctId(distinctId)
            val isPersonless = resolvedDistinctId.isNullOrBlank()
            val mergedProperties = properties?.toMutableMap() ?: mutableMapOf()
            val captureDistinctId = resolvedDistinctId ?: UUID.randomUUID().toString()

            if (isPersonless) {
                mergedProperties.putIfAbsent(PROCESS_PERSON_PROFILE_PROPERTY, false)
            }

            return PostHogResolvedCaptureContext(
                distinctId = captureDistinctId,
                properties = mergedProperties.ifEmpty { null },
            )
        }

        private fun resolveContext(
            data: PostHogRequestContextData,
            base: PostHogRequestContextData?,
        ): PostHogRequestContextData {
            val mergedProperties = base?.properties?.toMutableMap() ?: mutableMapOf()
            data.properties?.let { mergedProperties.putAll(it) }

            val distinctId = if (data.distinctId.isNullOrBlank()) base?.distinctId else data.distinctId
            val sessionId = if (data.sessionId.isNullOrBlank()) base?.sessionId else data.sessionId
            val childOverridesSessionId = !data.sessionId.isNullOrBlank()
            val childProvidedSessionProperty = data.properties?.containsKey(SESSION_ID_PROPERTY) == true
            if (childOverridesSessionId && !sessionId.isNullOrBlank() && !childProvidedSessionProperty) {
                mergedProperties[SESSION_ID_PROPERTY] = sessionId
            }

            return PostHogRequestContextData(
                distinctId = distinctId,
                sessionId = sessionId,
                properties = mergedProperties.ifEmpty { null },
            )
        }

        private fun firstHeaderValue(
            headers: Map<String, *>?,
            headerName: String,
        ): String? {
            if (headers == null) {
                return null
            }

            for ((key, value) in headers) {
                if (key.equals(headerName, ignoreCase = true)) {
                    return firstHeaderValue(value)
                }
            }

            return null
        }

        private fun firstHeaderValue(value: Any?): String? =
            when (value) {
                is String -> value
                is Iterable<*> -> value.firstOrNull() as? String
                is Array<*> -> value.firstOrNull() as? String
                else -> null
            }
    }

    /**
     * Active request context scope. Close it to restore the previous context.
     */
    public class Scope internal constructor(private val previous: PostHogRequestContextData?) : AutoCloseable {
        private var closed: Boolean = false

        override fun close() {
            if (closed) {
                return
            }

            if (previous == null) {
                currentContext.remove()
            } else {
                currentContext.set(previous)
            }
            closed = true
        }
    }
}

/**
 * Values applied to captures made inside a [PostHogRequestContext] scope.
 */
public class PostHogRequestContextData public constructor(
    public val distinctId: String? = null,
    public val sessionId: String? = null,
    public val properties: Map<String, Any>? = null,
)

internal data class PostHogResolvedCaptureContext(
    val distinctId: String,
    val properties: Map<String, Any>?,
)
