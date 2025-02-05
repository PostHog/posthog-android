package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class PostHogPrintLoggerTest {
    private val outputStreamCaptor = ByteArrayOutputStream()

    @BeforeTest
    fun `set up`() {
        System.setOut(PrintStream(outputStreamCaptor))
    }

    private fun getSut(debug: Boolean = false): PostHogPrintLogger {
        val config =
            PostHogConfig(API_KEY).apply {
                this.debug = debug
            }
        return PostHogPrintLogger(config)
    }

    @Test
    fun `logs into system out if enabled`() {
        val sut = getSut(true)

        sut.log("test")

        assertEquals("test", outputStreamCaptor.toString().trim())
    }

    @Test
    fun `does not log into system out if disabled`() {
        val sut = getSut()

        sut.log("test")

        assertTrue(outputStreamCaptor.toString().trim().isEmpty())
    }
}
