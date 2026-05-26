package com.posthog.logs

/**
 * Severity level for a captured log record.
 *
 * Constants are declared in severity order so `>=` comparisons on
 * [severityNumber] give the expected result — for example, "warn and
 * worse" is `severity.severityNumber >= PostHogLogSeverity.WARN.severityNumber`.
 *
 * Maps to OpenTelemetry severity numbers (`TRACE=1`, `DEBUG=5`, `INFO=9`,
 * `WARN=13`, `ERROR=17`, `FATAL=21`) for the OTLP wire format.
 *
 * ### Usage
 *
 * ```kotlin
 * // Direct use with the generic logger entry point:
 * PostHog.logger.log("loading", severity = PostHogLogSeverity.DEBUG)
 *
 * // Map a string from an external logger (e.g. Timber, slf4j):
 * val sev = PostHogLogSeverity.from("WARN") ?: PostHogLogSeverity.INFO
 *
 * // Filter inside a beforeSend hook:
 * config.logs.addBeforeSend { record ->
 *     if (record.level.severityNumber < PostHogLogSeverity.INFO.severityNumber) null
 *     else record
 * }
 * ```
 */
public enum class PostHogLogSeverity(
    /** OTLP `severityNumber` (1, 5, 9, 13, 17, 21). */
    public val severityNumber: Int,
    /** OTLP `severityText` (lower-case). */
    public val severityText: String,
) {
    TRACE(1, "trace"),
    DEBUG(5, "debug"),
    INFO(9, "info"),
    WARN(13, "warn"),
    ERROR(17, "error"),
    FATAL(21, "fatal"),
    ;

    public companion object {
        /** Tolerates surrounding whitespace and casing. Returns `null` for unknown values. */
        @JvmStatic
        public fun from(name: String): PostHogLogSeverity? {
            val normalized = name.trim().lowercase()
            return entries.firstOrNull { it.severityText == normalized }
        }
    }
}
