package com.posthog.internal.errortracking

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.internal.PostHogSerializer
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class CrashReportSizeLimitTest {
    private val serializer = PostHogSerializer(PostHogConfig("phc_test"))

    // Quotes double in size once JSON-escaped.
    private val quotes = "\"".repeat(300)

    private fun event() =
        PostHogEvent(
            "\$exception",
            "distinct-id",
            properties =
                mutableMapOf(
                    "\$exception_list" to listOf(mapOf("value" to quotes)),
                    CRASH_REPORT_PROPERTY to "Abort message: '$quotes'",
                ),
        )

    @Test
    fun `drops the crash report when JSON escaping pushes the event over the limit`() {
        val kept = event()
        val dropped = event()

        assertFalse(removeOversizedCrashReport(kept, serializer::serializeObject, maxBytes = 2000))
        assertTrue(removeOversizedCrashReport(dropped, serializer::serializeObject, maxBytes = 1200))
        assertTrue(kept.properties!!.containsKey(CRASH_REPORT_PROPERTY))
        assertFalse(dropped.properties!!.containsKey(CRASH_REPORT_PROPERTY))
        assertTrue(dropped.properties!!.containsKey("\$exception_list"))
    }

    @Test
    fun `drops a report longer than the limit without serializing`() {
        val event = event()

        assertTrue(removeOversizedCrashReport(event, { error("must not serialize") }, maxBytes = 100))
        assertFalse(event.properties!!.containsKey(CRASH_REPORT_PROPERTY))
    }
}
