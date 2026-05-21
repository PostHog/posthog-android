package com.posthog.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

internal class PostHogLogsConfigTest {
    private fun record(body: String = "hi") = PostHogLogRecord(body = body)

    @Test
    fun `runBeforeSend with no hooks returns the record unchanged`() {
        val config = PostHogLogsConfig()
        val input = record()
        val result = config.runBeforeSend(input)
        // No allocation, returns the same instance.
        assertSame(input, result)
    }

    @Test
    fun `runBeforeSend with no hooks drops blank body`() {
        val config = PostHogLogsConfig()
        // Blank-body sentinel must be enforced even without hooks so callers
        // can rely on the documented drop contract.
        assertNull(config.runBeforeSend(record(body = "   ")))
        assertNull(config.runBeforeSend(record(body = "")))
        assertNull(config.runBeforeSend(record(body = "\t\n")))
    }

    @Test
    fun `single beforeSend hook can mutate via copy`() {
        val config = PostHogLogsConfig()
        config.addBeforeSend { it.copy(level = PostHogLogSeverity.WARN) }
        val result = config.runBeforeSend(record(body = "ok"))!!
        assertEquals(PostHogLogSeverity.WARN, result.level)
    }

    @Test
    fun `beforeSend returning null short-circuits the chain and drops`() {
        val config = PostHogLogsConfig()
        var laterHookRan = false
        config.addBeforeSend { null }
        config.addBeforeSend {
            laterHookRan = true
            it
        }
        val result = config.runBeforeSend(record())
        assertNull(result)
        // Later hook must not see the dropped record.
        assertEquals(false, laterHookRan)
    }

    @Test
    fun `beforeSend hooks compose left-to-right`() {
        val config = PostHogLogsConfig()
        config.addBeforeSend { it.copy(body = it.body + " A") }
        config.addBeforeSend { it.copy(body = it.body + " B") }
        val result = config.runBeforeSend(record(body = "x"))!!
        assertEquals("x A B", result.body)
    }

    @Test
    fun `beforeSend hook mutating body to blank drops`() {
        val config = PostHogLogsConfig()
        config.addBeforeSend { it.copy(body = "   ") }
        assertNull(config.runBeforeSend(record(body = "non-blank")))
    }

    @Test
    fun `removeBeforeSend removes a previously-added hook`() {
        val config = PostHogLogsConfig()
        val drop = PostHogBeforeSendLog { null }
        config.addBeforeSend(drop)
        assertNull(config.runBeforeSend(record()))
        config.removeBeforeSend(drop)
        val result = config.runBeforeSend(record())
        assertEquals("hi", result?.body)
    }
}
