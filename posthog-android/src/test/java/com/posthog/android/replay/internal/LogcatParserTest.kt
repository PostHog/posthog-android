package com.posthog.android.replay.internal

import java.time.Instant
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class LogcatParserTest {
    @Test
    fun `parses realistic epoch logcat line as an exact UTC instant`() {
        val parser = LogcatParser()

        val log = parser.parse("1721057445.123  123  456 E MyTag  : message")

        assertNotNull(log)
        assertEquals(Instant.parse("2024-07-15T15:30:45.123Z").toEpochMilli(), log.time.timeInMillis)
        assertEquals(TimeZone.getTimeZone("UTC"), log.time.timeZone)
        assertEquals("error", log.level)
        assertEquals("MyTag  ", log.tag)
        assertEquals("message", log.text)
    }

    @Test
    fun `epoch parsing is independent of the process default timezone`() {
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))

            val log = LogcatParser().parse("1735689599.987654  321  654 W YearEnd: warning")

            assertNotNull(log)
            assertEquals(Instant.parse("2024-12-31T23:59:59.987Z").toEpochMilli(), log.time.timeInMillis)
            assertEquals("warn", log.level)
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun `rejects threadtime lines to avoid ambiguous local wall clock parsing`() {
        val log = LogcatParser().parse("12-31 23:59:59.987  321  654 W YearEnd: warning")

        assertNull(log)
    }
}
