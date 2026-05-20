package com.posthog.logs

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogLogRecordTest {
    @Test
    fun `timeUnixNano defaults to current time and observedTimeUnixNano mirrors it`() {
        val before = System.currentTimeMillis() * 1_000_000L
        val record = PostHogLogRecord(body = "hello")
        val after = System.currentTimeMillis() * 1_000_000L

        val time = record.timeUnixNano.toLong()
        assertTrue(time in before..after, "timeUnixNano ($time) not in [$before, $after]")
        assertEquals(record.timeUnixNano, record.observedTimeUnixNano)
    }

    @Test
    fun `storage round-trip preserves all fields`() {
        val original =
            PostHogLogRecord(
                body = "round-trip me",
                level = PostHogLogSeverity.WARN,
                attributes = mapOf("k" to "v", "n" to 42),
                traceId = "0102030405060708090a0b0c0d0e0f10",
                spanId = "0102030405060708",
                traceFlags = 1,
                distinctId = "user-1",
                sessionId = "sess-1",
                screenName = "Home",
                featureFlagKeys = listOf("flag-a", "flag-b"),
                appState = "foreground",
                timeUnixNano = "1700000000000000000",
                observedTimeUnixNano = "1700000000000000001",
            )

        val map = original.toStorageMap()
        val restored = PostHogLogRecord.fromStorageMap(map)

        assertNotNull(restored)
        assertEquals(original.body, restored.body)
        assertEquals(original.level, restored.level)
        assertEquals(original.attributes, restored.attributes)
        assertEquals(original.traceId, restored.traceId)
        assertEquals(original.spanId, restored.spanId)
        assertEquals(original.traceFlags, restored.traceFlags)
        assertEquals(original.distinctId, restored.distinctId)
        assertEquals(original.sessionId, restored.sessionId)
        assertEquals(original.screenName, restored.screenName)
        assertEquals(original.featureFlagKeys, restored.featureFlagKeys)
        assertEquals(original.appState, restored.appState)
        assertEquals(original.timeUnixNano, restored.timeUnixNano)
        assertEquals(original.observedTimeUnixNano, restored.observedTimeUnixNano)
    }

    @Test
    fun `storage round-trip through serializer preserves wire-relevant fields`() {
        // Goes through the actual production codec: toStorageMap -> Gson serialize ->
        // file bytes -> Gson deserialize -> fromStorageMap. Catches type-erasure
        // surprises (e.g. Gson's Int->Double widening on Map<String, Any> values).
        val config = PostHogConfig(API_KEY)
        val original =
            PostHogLogRecord(
                body = "round-trip",
                level = PostHogLogSeverity.WARN,
                attributes = mapOf("k" to "v", "n" to 42),
                traceFlags = 1,
                featureFlagKeys = listOf("a"),
            )
        val buf = ByteArrayOutputStream()
        config.serializer.serialize(original.toStorageMap(), buf.writer().buffered())
        val map: Map<String, Any> =
            config.serializer.deserialize(
                ByteArrayInputStream(buf.toByteArray()).reader().buffered(),
            )!!
        val restored = PostHogLogRecord.fromStorageMap(map)!!
        assertEquals("round-trip", restored.body)
        assertEquals(PostHogLogSeverity.WARN, restored.level)
        assertEquals(1, restored.traceFlags) // proves Number -> Int coercion holds
        assertEquals(listOf("a"), restored.featureFlagKeys)
    }

    @Test
    fun `storage map omits null optionals`() {
        val record = PostHogLogRecord(body = "minimal")
        val map = record.toStorageMap()
        // Assert key absence, not just null value — a mutated `?.let { map[k] = it }`
        // could put null under the key and `assertNull(map[k])` would still pass.
        assertFalse(map.containsKey("traceId"))
        assertFalse(map.containsKey("spanId"))
        assertFalse(map.containsKey("traceFlags"))
        assertFalse(map.containsKey("distinctId"))
        assertFalse(map.containsKey("sessionId"))
        assertFalse(map.containsKey("screenName"))
        assertFalse(map.containsKey("appState"))
        assertFalse(map.containsKey("attributes")) // empty attributes are omitted
    }

    @Test
    fun `storage map includes every optional when set`() {
        val record =
            PostHogLogRecord(
                body = "set everything",
                attributes = mapOf("k" to "v"),
                traceId = "tid",
                spanId = "sid",
                traceFlags = 1,
                distinctId = "did",
                sessionId = "sess",
                screenName = "Home",
                appState = "foreground",
            )
        val map = record.toStorageMap()
        assertEquals("tid", map["traceId"])
        assertEquals("sid", map["spanId"])
        assertEquals(1, map["traceFlags"])
        assertEquals("did", map["distinctId"])
        assertEquals("sess", map["sessionId"])
        assertEquals("Home", map["screenName"])
        assertEquals("foreground", map["appState"])
        assertEquals(mapOf("k" to "v"), map["attributes"])
    }

    @Test
    fun `fromStorageMap ignores wrong-typed optional fields`() {
        // Wrong type at an optional key is treated as absent, not a crash.
        // Locks in the `as?` fallback path on each optional.
        val map =
            mapOf<String, Any>(
                "body" to "x",
                "attributes" to "not-a-map",
                "featureFlagKeys" to "not-a-list",
                "traceId" to 123,
                "spanId" to 456,
                "distinctId" to true,
                "sessionId" to listOf("nope"),
                "screenName" to mapOf("a" to "b"),
                "appState" to 99,
            )
        val record = PostHogLogRecord.fromStorageMap(map)
        assertNotNull(record)
        assertEquals(emptyMap<String, Any>(), record.attributes)
        assertEquals(emptyList<String>(), record.featureFlagKeys)
        assertNull(record.traceId)
        assertNull(record.spanId)
        assertNull(record.distinctId)
        assertNull(record.sessionId)
        assertNull(record.screenName)
        assertNull(record.appState)
    }

    @Test
    fun `fromStorageMap defaults level to INFO when unknown`() {
        val map = mapOf<String, Any>("body" to "x", "level" to "verbose")
        val record = PostHogLogRecord.fromStorageMap(map)
        assertNotNull(record)
        assertEquals(PostHogLogSeverity.INFO, record.level)
    }

    @Test
    fun `fromStorageMap returns null without body`() {
        assertNull(PostHogLogRecord.fromStorageMap(mapOf("level" to "info")))
    }

    @Test
    fun `fromStorageMap accepts numeric traceFlags`() {
        val map = mapOf<String, Any>("body" to "x", "traceFlags" to 1.0)
        val record = PostHogLogRecord.fromStorageMap(map)
        assertNotNull(record)
        assertEquals(1, record.traceFlags)
    }
}
