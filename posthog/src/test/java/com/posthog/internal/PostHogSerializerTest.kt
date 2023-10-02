package com.posthog.internal

import com.google.gson.internal.bind.util.ISO8601Utils
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.apiKey
import com.posthog.date
import com.posthog.generateEvent
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.text.ParsePosition
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class PostHogSerializerTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private fun getSut(): PostHogSerializer {
        val config = PostHogConfig(apiKey)
        return PostHogSerializer(config)
    }

    private fun assertEvent(event: PostHogEvent) {
        assertEquals("event", event.event)
        assertEquals("distinctId", event.distinctId)
        assertEquals("value", event.properties!!["prop"])
        assertEquals("8c04e5c1-8f6e-4002-96fd-1804799b6ffe", event.uuid.toString())

        val date = ISO8601Utils.parse("2023-09-20T11:58:49.000Z", ParsePosition(0))
        assertTrue(event.timestamp.compareTo(date) == 0)
    }

    @AfterTest
    fun `set down`() {
        tmpDir.root.deleteRecursively()
    }

    @Test
    fun `serializes event to disk`() {
        val sut = getSut()

        val event = generateEvent()

        val file = tmpDir.newFile()
        sut.serialize(event, file.outputStream().writer().buffered())

        val expectedJson = """{"event":"event","distinct_id":"distinctId","properties":{"prop":"value"},"timestamp":"2023-09-20T11:58:49.000Z","uuid":"8c04e5c1-8f6e-4002-96fd-1804799b6ffe"}"""

        assertEquals(expectedJson, file.readText())
    }

    @Test
    fun `deserializes event from disk`() {
        val sut = getSut()

        val event = generateEvent()

        val file = tmpDir.newFile()
        sut.serialize(event, file.outputStream().writer().buffered())

        val theEvent = sut.deserialize<PostHogEvent?>(file.inputStream().reader().buffered())

        assertEvent(theEvent!!)
    }

    @Test
    fun `serializes batch api`() {
        val sut = getSut()

        val event = generateEvent()
        val batch = PostHogBatchEvent(apiKey, listOf(event), sentAt = date)

        val file = tmpDir.newFile()
        sut.serialize(batch, file.outputStream().writer().buffered())

        val expectedJson = """{"api_key":"_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI","batch":[{"event":"event","distinct_id":"distinctId","properties":{"prop":"value"},"timestamp":"2023-09-20T11:58:49.000Z","uuid":"8c04e5c1-8f6e-4002-96fd-1804799b6ffe"}],"sent_at":"2023-09-20T11:58:49.000Z"}"""

        assertEquals(expectedJson, file.readText())
    }
}
