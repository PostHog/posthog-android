package com.posthog.logs

import com.posthog.internal.PostHogDateProvider

/** A captured log entry queued for delivery to PostHog's logs ingestion. */
internal data class PostHogLogRecord(
    val body: String,
    val level: PostHogLogSeverity = PostHogLogSeverity.INFO,
    val attributes: Map<String, Any> = emptyMap(),
    val traceId: String? = null,
    val spanId: String? = null,
    val traceFlags: Int? = null,
    val distinctId: String? = null,
    val sessionId: String? = null,
    val screenName: String? = null,
    val featureFlagKeys: List<String> = emptyList(),
    val appState: String? = null,
    val timeUnixNano: String = nanosNow(),
    val observedTimeUnixNano: String = timeUnixNano,
) {
    /**
     * The on-disk shape consumed by [fromStorageMap]. Decoupled from the OTLP
     * wire format so the wire format can change without rewriting persisted
     * records.
     */
    fun toStorageMap(): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        map["body"] = body
        map["level"] = level.severityText
        map["timeUnixNano"] = timeUnixNano
        map["observedTimeUnixNano"] = observedTimeUnixNano
        map["featureFlagKeys"] = featureFlagKeys
        if (attributes.isNotEmpty()) map["attributes"] = attributes
        traceId?.let { map["traceId"] = it }
        spanId?.let { map["spanId"] = it }
        traceFlags?.let { map["traceFlags"] = it }
        distinctId?.let { map["distinctId"] = it }
        sessionId?.let { map["sessionId"] = it }
        screenName?.let { map["screenName"] = it }
        appState?.let { map["appState"] = it }
        return map
    }

    companion object {
        fun fromStorageMap(map: Map<String, Any>): PostHogLogRecord? {
            val body = map["body"] as? String ?: return null
            val levelName = map["level"] as? String ?: "info"
            val level = PostHogLogSeverity.from(levelName) ?: PostHogLogSeverity.INFO

            @Suppress("UNCHECKED_CAST")
            val attributes = (map["attributes"] as? Map<String, Any>) ?: emptyMap()
            val timeUnixNano = map["timeUnixNano"] as? String ?: nanosNow()
            val observedTimeUnixNano = map["observedTimeUnixNano"] as? String ?: timeUnixNano

            @Suppress("UNCHECKED_CAST")
            val featureFlagKeys = (map["featureFlagKeys"] as? List<String>) ?: emptyList()
            val traceFlags =
                when (val raw = map["traceFlags"]) {
                    is Number -> raw.toInt()
                    else -> null
                }
            return PostHogLogRecord(
                body = body,
                level = level,
                attributes = attributes,
                traceId = map["traceId"] as? String,
                spanId = map["spanId"] as? String,
                traceFlags = traceFlags,
                distinctId = map["distinctId"] as? String,
                sessionId = map["sessionId"] as? String,
                screenName = map["screenName"] as? String,
                featureFlagKeys = featureFlagKeys,
                appState = map["appState"] as? String,
                timeUnixNano = timeUnixNano,
                observedTimeUnixNano = observedTimeUnixNano,
            )
        }

        /**
         * Nanoseconds since epoch. Pass a [PostHogDateProvider] for
         * deterministic timestamps in tests; defaults to the system clock.
         */
        internal fun nanosNow(dateProvider: PostHogDateProvider? = null): String {
            val millis = dateProvider?.currentTimeMillis() ?: System.currentTimeMillis()
            return (millis * 1_000_000L).toString()
        }
    }
}
