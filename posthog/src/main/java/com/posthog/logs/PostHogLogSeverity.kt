package com.posthog.logs

/**
 * Severity level for a captured log record.
 *
 * Constants are declared in severity order so `>=` comparisons on
 * [severityNumber] give the expected result.
 *
 * Maps to OpenTelemetry severity numbers (`TRACE=1`, `DEBUG=5`, `INFO=9`,
 * `WARN=13`, `ERROR=17`, `FATAL=21`) for the OTLP wire format.
 */
internal enum class PostHogLogSeverity(
    val severityNumber: Int,
    val severityText: String,
) {
    TRACE(1, "trace"),
    DEBUG(5, "debug"),
    INFO(9, "info"),
    WARN(13, "warn"),
    ERROR(17, "error"),
    FATAL(21, "fatal"),
    ;

    companion object {
        /** Tolerates surrounding whitespace and casing. Returns `null` for unknown values. */
        fun from(name: String): PostHogLogSeverity? {
            val normalized = name.trim().lowercase()
            return entries.firstOrNull { it.severityText == normalized }
        }
    }
}
