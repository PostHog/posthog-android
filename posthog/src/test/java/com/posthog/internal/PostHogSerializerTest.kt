package com.posthog.internal

import com.google.gson.internal.bind.util.ISO8601Utils
import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.date
import com.posthog.generateEvent
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringWriter
import java.math.BigDecimal
import java.math.BigInteger
import java.text.ParsePosition
import java.util.Date
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class PostHogSerializerTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private fun getSut(): PostHogSerializer {
        val config = PostHogConfig(API_KEY)
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

        val expectedJson =
            """
            {
              "event": "event",
              "distinct_id": "distinctId",
              "properties": {
                "prop": "value"
              },
              "timestamp": "2023-09-20T11:58:49.000Z",
              "uuid": "8c04e5c1-8f6e-4002-96fd-1804799b6ffe"
            }
            """.replace(" ", "").replace("\n", "")

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
        val batch = PostHogBatchEvent(API_KEY, listOf(event), sentAt = date)

        val file = tmpDir.newFile()
        sut.serialize(batch, file.outputStream().writer().buffered())

        val expectedJson =
            """
            {
              "api_key": "_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI",
              "batch": [
                {
                  "event": "event",
                  "distinct_id": "distinctId",
                  "properties": {
                    "prop": "value"
                  },
                  "timestamp": "2023-09-20T11:58:49.000Z",
                  "uuid": "8c04e5c1-8f6e-4002-96fd-1804799b6ffe"
                }
              ],
              "sent_at": "2023-09-20T11:58:49.000Z"
            }
            """.replace(" ", "").replace("\n", "")

        assertEquals(expectedJson, file.readText())
    }

    @Test
    fun `serializes legacy file`() {
        val sut = getSut()

        val theFile = File("src/test/resources/legacy/_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI")
        val legacy =
            QueueFile.Builder(theFile)
                .forceLegacy(true)
                .build()

        assertEquals(19, legacy.size())
        val it = legacy.iterator()
        val events = mutableListOf<PostHogEvent>()
        while (it.hasNext()) {
            val bytes = it.next()

            val event = sut.deserialize<PostHogEvent>(bytes.inputStream().reader().buffered())
            assertNotNull(event)
            events.add(event)
        }
        assertEquals(19, events.size)
    }

    @Test
    fun `serializes legacy file 2`() {
        val sut = getSut()

        val theFile = File("src/test/resources/legacy/_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI2")
        val legacy =
            QueueFile.Builder(theFile)
                .forceLegacy(true)
                .build()

        assertEquals(16, legacy.size())
        val it = legacy.iterator()
        val events = mutableListOf<PostHogEvent>()
        while (it.hasNext()) {
            val bytes = it.next()

            val event = sut.deserialize<PostHogEvent>(bytes.inputStream().reader().buffered())
            assertNotNull(event)
            events.add(event)
        }
        assertEquals(16, events.size)
    }

    @Test
    fun `serializes different types in personProperties`() {
        class CustomTestData(val field1: String, val field2: Int)

        val sut = getSut()
        val personProperties =
            mapOf<String, Any?>(
                "string_prop" to "test_value",
                "int_prop" to 42,
                "long_prop" to 1234567890L,
                "double_prop" to 3.14159,
                "boolean_prop" to true,
                "date_prop" to Date(1234567890000L),
                "uuid_prop" to UUID.fromString("12345678-90ab-cdef-1234-567890abcdef"),
                "bigint_prop" to BigInteger.valueOf(9223372036854775807L),
                "bigdecimal_prop" to BigDecimal("123.456"),
                "custom_prop" to CustomTestData("custom", 999),
                "null_prop" to null,
            )

        val flagsRequest =
            PostHogFlagsRequest(
                apiKey = API_KEY,
                distinctId = "test_user",
                personProperties = personProperties,
            )

        val serialized = StringWriter()
        sut.serialize(flagsRequest, serialized)
        val actualJson = serialized.toString()
        val expectedJson =
            """
            {
                "api_key": "_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI",
                "distinct_id": "test_user",
                "person_properties": {
                    "string_prop": "test_value",
                    "int_prop": 42,
                    "long_prop": 1234567890,
                    "double_prop": 3.14159,
                    "boolean_prop": true,
                    "date_prop": "2009-02-13T23:31:30.000Z",
                    "uuid_prop": "12345678-90ab-cdef-1234-567890abcdef",
                    "bigint_prop": 9223372036854775807,
                    "bigdecimal_prop": 123.456,
                    "custom_prop": {
                        "field1": "custom",
                        "field2": 999
                    }
                }
            }
            """.replace(" ", "").replace("\n", "")

        assertEquals(expectedJson, actualJson)
    }

    @Test
    fun `filters unserializable values in nested maps while preserving structure`() {
        val sut = getSut()

        // Create a nested map with an unserializable value deep in the tree
        val unserializableValue = Thread.currentThread()
        val properties =
            mapOf<String, Any>(
                "good" to "yes",
                "bad" to
                    mapOf<String, Any>(
                        "good" to "yes",
                        "bad" to unserializableValue,
                    ),
                "in_an_array" to
                    listOf<Any>(
                        "good",
                        unserializableValue,
                    ),
            )

        val eventUuid = UUID.fromString("12345678-90ab-cdef-1234-567890abcdef")
        val event =
            PostHogEvent(
                event = "test_event",
                distinctId = "user123",
                properties = properties.toMutableMap(),
                timestamp = Date(1234567890000L),
                uuid = eventUuid,
            )

        val serialized = StringWriter()
        sut.serialize(event, serialized)
        val actualJson = serialized.toString()

        // The result should preserve the nested "bad" map but only drop the unserializable leaf value
        val expectedJson =
            """
            {
                "event": "test_event",
                "distinct_id": "user123",
                "properties": {
                    "good": "yes",
                    "bad": {
                        "good": "yes"
                    },
                    "in_an_array": [
                        "good"
                    ]
                },
                "timestamp": "2009-02-13T23:31:30.000Z",
                "uuid": "12345678-90ab-cdef-1234-567890abcdef"
            }
            """.replace(" ", "").replace("\n", "")

        assertEquals(expectedJson, actualJson)
    }
}
