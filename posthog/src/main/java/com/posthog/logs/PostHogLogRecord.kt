package com.posthog.logs

import com.posthog.internal.PostHogDateProvider
import java.util.concurrent.atomic.AtomicLong

/**
 * A captured log entry queued for delivery to PostHog's logs ingestion.
 *
 * You don't construct these directly under normal use — call
 * [com.posthog.PostHog.logger] methods instead. The record is surfaced as
 * the input to [PostHogBeforeSendLog] hooks so you can inspect or rewrite
 * it before it ships.
 *
 * ```kotlin
 * config.logs.addBeforeSend { record: PostHogLogRecord ->
 *     // Inspect:
 *     val severity = record.level
 *     val body = record.body
 *
 *     // Rewrite via copy() (data class):
 *     record.copy(body = body.replace(SECRET_REGEX, "[redacted]"))
 * }
 * ```
 *
 * @property body Log message body. Blank records are dropped after `beforeSend`.
 * @property level Severity; defaults to [PostHogLogSeverity.INFO].
 * @property attributes Structured per-record attributes attached by the
 *   caller.
 * @property traceId W3C trace id, when capture is part of a distributed
 *   trace.
 * @property spanId W3C span id paired with [traceId].
 * @property traceFlags W3C trace flags byte (e.g. `0x01` for sampled).
 * @property distinctId Snapshot of the active PostHog distinct id at
 *   capture time.
 * @property sessionId Snapshot of the active session id at capture time.
 * @property screenName Last screen name reported via
 *   [com.posthog.PostHog.screen].
 * @property featureFlagKeys Snapshot of feature flag keys that evaluated
 *   to active (truthy boolean or any multivariant value) at capture
 *   time. Use this to filter logs by flag state in the PostHog UI.
 * @property appState `"foreground"` or `"background"` at capture time.
 * @property timeUnixNano Advanced/observability field. Capture time as
 *   nanoseconds since epoch, encoded as a string to avoid i64 overflow
 *   on the JSON wire.
 * @property observedTimeUnixNano Advanced/observability field. Defaults
 *   to [timeUnixNano]; differs only when the SDK observes a record after
 *   the original timestamp (e.g. replay from persisted storage).
 */
public data class PostHogLogRecord(
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
    internal fun toStorageMap(): Map<String, Any> {
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

    public companion object {
        internal fun fromStorageMap(map: Map<String, Any>): PostHogLogRecord? {
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

        // Strictly-monotonic counter so two log calls in the same millisecond
        // (tight loops, batch flushes) don't collide on `timeUnixNano`.
        // Backs the in-process ordering OTel collectors fall back to when
        // wall-clock time doesn't advance between records.
        private val lastNanos = AtomicLong(0)

        /**
         * Nanoseconds since epoch, guaranteed strictly monotonic across
         * concurrent calls. Pass a [PostHogDateProvider] for deterministic
         * timestamps in tests; defaults to the system clock.
         */
        internal fun nanosNow(dateProvider: PostHogDateProvider? = null): String {
            val millis = dateProvider?.currentTimeMillis() ?: System.currentTimeMillis()
            val now = millis * 1_000_000L
            while (true) {
                val prev = lastNanos.get()
                // If the wall clock advanced, use it; otherwise bump prev by 1ns
                // so the output is still strictly increasing.
                val next = if (now > prev) now else prev + 1L
                if (lastNanos.compareAndSet(prev, next)) {
                    return next.toString()
                }
            }
        }
    }
}
