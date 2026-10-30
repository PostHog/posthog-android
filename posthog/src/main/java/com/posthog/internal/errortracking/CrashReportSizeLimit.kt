package com.posthog.internal.errortracking

import com.posthog.PostHogEvent

internal const val CRASH_REPORT_PROPERTY = "\$exception_crash_report"

// Capture rejects events over its default 1 MB limit, and a single rejected
// event is dropped, so leave headroom for the request envelope.
private const val MAX_CRASH_EVENT_BYTES = 900_000

/**
 * Removes [CRASH_REPORT_PROPERTY] when the serialized event would exceed
 * [maxBytes], so an optional crash report cannot cost the crash event itself.
 * Runs on the fully built event, after context enrichment and `beforeSend`.
 */
internal fun removeOversizedCrashReport(
    event: PostHogEvent,
    serialize: (Any) -> String?,
    maxBytes: Int = MAX_CRASH_EVENT_BYTES,
): Boolean {
    val properties = event.properties ?: return false
    val report = properties[CRASH_REPORT_PROPERTY] as? String ?: return false
    // The serialized event is never smaller than the report itself, so skip serializing one that cannot fit.
    val tooLarge =
        report.length > maxBytes ||
            runCatching { serialize(event)?.toByteArray(Charsets.UTF_8)?.size }.getOrNull()?.let { it > maxBytes } == true
    if (!tooLarge) {
        return false
    }
    properties.remove(CRASH_REPORT_PROPERTY)
    return true
}
