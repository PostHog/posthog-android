package com.posthog.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class PostHogLogSeverityTest {
    @Test
    fun `severity numbers match OTLP spec`() {
        assertEquals(1, PostHogLogSeverity.TRACE.severityNumber)
        assertEquals(5, PostHogLogSeverity.DEBUG.severityNumber)
        assertEquals(9, PostHogLogSeverity.INFO.severityNumber)
        assertEquals(13, PostHogLogSeverity.WARN.severityNumber)
        assertEquals(17, PostHogLogSeverity.ERROR.severityNumber)
        assertEquals(21, PostHogLogSeverity.FATAL.severityNumber)
    }

    @Test
    fun `severity text is the lowercased OTLP name`() {
        assertEquals("trace", PostHogLogSeverity.TRACE.severityText)
        assertEquals("debug", PostHogLogSeverity.DEBUG.severityText)
        assertEquals("info", PostHogLogSeverity.INFO.severityText)
        assertEquals("warn", PostHogLogSeverity.WARN.severityText)
        assertEquals("error", PostHogLogSeverity.ERROR.severityText)
        assertEquals("fatal", PostHogLogSeverity.FATAL.severityText)
    }

    @Test
    fun `from tolerates whitespace and casing for every severity`() {
        // Iterate so a future severity addition picks up coverage automatically.
        // A regression that swapped `firstOrNull { it.severityText == normalized }`
        // for a case-sensitive valueOf-style lookup would only fail one or two
        // entries here unless every case is checked.
        PostHogLogSeverity.entries.forEach { severity ->
            assertEquals(severity, PostHogLogSeverity.from(severity.severityText))
            assertEquals(severity, PostHogLogSeverity.from(severity.severityText.uppercase()))
            assertEquals(severity, PostHogLogSeverity.from("  ${severity.severityText}\n"))
        }
    }

    @Test
    fun `from returns null for unknown name`() {
        assertNull(PostHogLogSeverity.from("verbose"))
        assertNull(PostHogLogSeverity.from(""))
    }
}
