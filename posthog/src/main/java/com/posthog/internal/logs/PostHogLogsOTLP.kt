package com.posthog.internal.logs

import com.posthog.internal.formatISO8601Date
import com.posthog.logs.PostHogLogRecord
import java.util.Date

/**
 * OpenTelemetry / OTLP-JSON serialization for log records.
 *
 * Emits the OTLP `LogsService` request shape
 * (`resourceLogs[].scopeLogs[].logRecords[]`) — distinct from PostHog's
 * internal events batch shape because the logs ingestion endpoint expects OTLP.
 */
internal object PostHogLogsOTLP {
    /**
     * Wraps a value as an OTLP `AnyValue`. Returns `null` for values that
     * have no representable OTLP form (which the caller should drop).
     *
     * `Int` / `Long` / `Short` / `Byte` map to `intValue` (encoded as a
     * string per proto3 JSON int64 rules). `Double` / `Float` map to
     * `doubleValue` for finite numbers and `stringValue` ("NaN" | "Infinity"
     * | "-Infinity") otherwise — JSON cannot represent those floats directly.
     */
    fun toAnyValue(value: Any?): Map<String, Any>? {
        if (value == null) return null
        return when (value) {
            is String -> mapOf("stringValue" to value)
            is Boolean -> mapOf("boolValue" to value)
            // Double / Float must precede Number so the NaN / Infinity sentinel path
            // keeps firing for non-finite floats that JSON can't express directly.
            is Double -> doubleAnyValue(value)
            is Float -> doubleAnyValue(value.toDouble())
            // Long / Int / Short / Byte / BigInteger / AtomicInteger all encode as
            // int64-as-string per proto3 JSON.
            is Number -> mapOf("intValue" to value.toLong().toString())
            is List<*> -> {
                val mapped = value.mapNotNull { toAnyValue(it) }
                mapOf("arrayValue" to mapOf("values" to mapped))
            }
            is Map<*, *> -> {
                val safe = LinkedHashMap<String, Any>()
                for ((k, v) in value) {
                    if (k is String && v != null) safe[k] = v
                }
                mapOf("kvlistValue" to mapOf("values" to toKeyValueList(safe)))
            }
            is Date -> mapOf("stringValue" to formatISO8601Date(value))
            // Last resort: stringify so the user gets something rather than a
            // silently dropped attribute.
            else -> mapOf("stringValue" to value.toString())
        }
    }

    private fun doubleAnyValue(value: Double): Map<String, Any> {
        if (value.isNaN()) return mapOf("stringValue" to "NaN")
        if (value.isInfinite()) {
            return mapOf("stringValue" to if (value > 0) "Infinity" else "-Infinity")
        }
        return mapOf("doubleValue" to value)
    }

    /**
     * Converts a `Map<String, Any>` to an OTLP `KeyValue[]` list, dropping
     * entries whose values cannot be represented. Keys are sorted so the
     * wire output is deterministic — easier on tests and diff-based debugging.
     */
    fun toKeyValueList(dict: Map<String, Any>): List<Map<String, Any>> {
        val result = ArrayList<Map<String, Any>>(dict.size)
        for (key in dict.keys.sorted()) {
            val raw = dict[key] ?: continue
            val value = toAnyValue(raw) ?: continue
            result.add(mapOf("key" to key, "value" to value))
        }
        return result
    }

    /**
     * Builds a single OTLP `LogRecord` element from a stored record.
     * Auto-attached context (distinctId, sessionId, screen.name, app.state,
     * feature_flags) is merged in *underneath* the user's `attributes` so
     * user-supplied keys win on collision.
     */
    fun buildLogRecord(record: PostHogLogRecord): Map<String, Any> {
        val attrs = LinkedHashMap<String, Any>()
        record.distinctId?.let { attrs["posthogDistinctId"] = it }
        record.sessionId?.let { attrs["sessionId"] = it }
        record.screenName?.let { attrs["screen.name"] = it }
        record.appState?.let { attrs["app.state"] = it }
        if (record.featureFlagKeys.isNotEmpty()) attrs["feature_flags"] = record.featureFlagKeys
        attrs.putAll(record.attributes)

        val json = LinkedHashMap<String, Any>()
        json["timeUnixNano"] = record.timeUnixNano
        json["observedTimeUnixNano"] = record.observedTimeUnixNano
        json["severityNumber"] = record.level.severityNumber
        json["severityText"] = record.level.severityText
        json["body"] = mapOf("stringValue" to record.body)
        if (attrs.isNotEmpty()) json["attributes"] = toKeyValueList(attrs)
        record.traceId?.let { json["traceId"] = it }
        record.spanId?.let { json["spanId"] = it }
        record.traceFlags?.let { json["flags"] = it }
        return json
    }

    /**
     * Layers SDK-managed resource attributes (`telemetry.sdk.name`,
     * `telemetry.sdk.version`) on top of the user-supplied
     * `resourceAttributes` so the SDK identification can't be shadowed.
     */
    fun buildResourceAttributes(
        userResourceAttributes: Map<String, Any>,
        sdkName: String,
        sdkVersion: String,
    ): Map<String, Any> {
        val attrs = LinkedHashMap<String, Any>()
        attrs.putAll(userResourceAttributes)
        attrs["telemetry.sdk.name"] = sdkName
        attrs["telemetry.sdk.version"] = sdkVersion
        return attrs
    }

    /**
     * Builds the full OTLP request payload.
     */
    fun buildPayload(
        records: List<PostHogLogRecord>,
        resourceAttributes: Map<String, Any>,
        sdkName: String,
        sdkVersion: String,
    ): Map<String, Any> {
        val mergedResourceAttrs = buildResourceAttributes(resourceAttributes, sdkName, sdkVersion)
        val logRecords = records.map { buildLogRecord(it) }
        return mapOf(
            "resourceLogs" to
                listOf(
                    mapOf(
                        "resource" to
                            mapOf(
                                "attributes" to toKeyValueList(mergedResourceAttrs),
                            ),
                        "scopeLogs" to
                            listOf(
                                mapOf(
                                    "scope" to
                                        mapOf(
                                            "name" to sdkName,
                                            "version" to sdkVersion,
                                        ),
                                    "logRecords" to logRecords,
                                ),
                            ),
                    ),
                ),
        )
    }
}
