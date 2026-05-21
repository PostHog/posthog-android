package com.posthog.logs

import kotlin.test.Test
import kotlin.test.assertEquals

internal class PostHogLoggerTest {
    private data class Captured(
        val message: String,
        val severity: PostHogLogSeverity,
        val attributes: Map<String, Any>?,
    )

    private class Capturing {
        val records = mutableListOf<Captured>()

        fun fn(
            message: String,
            severity: PostHogLogSeverity,
            attributes: Map<String, Any>?,
        ) {
            records.add(Captured(message, severity, attributes))
        }
    }

    private fun loggerFor(c: Capturing) = PostHogLogger(c::fn)

    @Test
    fun `log uses INFO as default severity`() {
        val c = Capturing()
        loggerFor(c).log("hello")
        assertEquals(PostHogLogSeverity.INFO, c.records.single().severity)
    }

    @Test
    fun `each shortcut routes to its severity and forwards attributes`() {
        // One test covers all six shortcuts so adding a future severity
        // surfaces a missing case here rather than living unnoticed.
        val c = Capturing()
        val logger = loggerFor(c)
        val attrs = mapOf("k" to "v")
        logger.trace("t", attrs)
        logger.debug("d", attrs)
        logger.info("i", attrs)
        logger.warn("w", attrs)
        logger.error("e", attrs)
        logger.fatal("f", attrs)

        val expected =
            listOf(
                PostHogLogSeverity.TRACE,
                PostHogLogSeverity.DEBUG,
                PostHogLogSeverity.INFO,
                PostHogLogSeverity.WARN,
                PostHogLogSeverity.ERROR,
                PostHogLogSeverity.FATAL,
            )
        assertEquals(expected, c.records.map { it.severity })
        // Attributes pass through verbatim on every shortcut.
        c.records.forEach { assertEquals(attrs, it.attributes) }
    }

    @Test
    fun `shortcuts default attributes to null`() {
        val c = Capturing()
        loggerFor(c).warn("w")
        assertEquals(null, c.records.single().attributes)
    }

    @Test
    fun `NO_OP swallows every call without invoking any capture`() {
        // The interface-default logger must not crash and must not invoke
        // SDK plumbing for callers that don't have a configured SDK.
        PostHogLogger.NO_OP.log("hi")
        PostHogLogger.NO_OP.info("ok", mapOf("k" to 1))
        PostHogLogger.NO_OP.fatal("nope")
        // No assertion needed — survival of the method calls is the contract.
    }
}
