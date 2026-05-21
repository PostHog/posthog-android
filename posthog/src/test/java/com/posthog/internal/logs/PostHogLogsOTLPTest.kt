package com.posthog.internal.logs

import com.posthog.logs.PostHogLogRecord
import com.posthog.logs.PostHogLogSeverity
import java.math.BigInteger
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogLogsOTLPTest {
    @Test
    fun `toAnyValue String maps to stringValue`() {
        assertEquals(mapOf("stringValue" to "hello"), PostHogLogsOTLP.toAnyValue("hello"))
    }

    @Test
    fun `toAnyValue Bool maps to boolValue`() {
        assertEquals(mapOf("boolValue" to true), PostHogLogsOTLP.toAnyValue(true))
        assertEquals(mapOf("boolValue" to false), PostHogLogsOTLP.toAnyValue(false))
    }

    @Test
    fun `toAnyValue integer types encode as intValue string per proto3`() {
        assertEquals(mapOf("intValue" to "42"), PostHogLogsOTLP.toAnyValue(42))
        assertEquals(mapOf("intValue" to "42"), PostHogLogsOTLP.toAnyValue(42L))
        assertEquals(mapOf("intValue" to "42"), PostHogLogsOTLP.toAnyValue(42.toShort()))
        assertEquals(mapOf("intValue" to "42"), PostHogLogsOTLP.toAnyValue(42.toByte()))
    }

    @Test
    fun `toAnyValue finite Double maps to doubleValue`() {
        assertEquals(mapOf("doubleValue" to 3.14), PostHogLogsOTLP.toAnyValue(3.14))
        assertEquals(mapOf("doubleValue" to 1.5), PostHogLogsOTLP.toAnyValue(1.5f))
    }

    @Test
    fun `toAnyValue non-finite Double maps to stringValue special sentinels`() {
        assertEquals(mapOf("stringValue" to "NaN"), PostHogLogsOTLP.toAnyValue(Double.NaN))
        assertEquals(
            mapOf("stringValue" to "Infinity"),
            PostHogLogsOTLP.toAnyValue(Double.POSITIVE_INFINITY),
        )
        assertEquals(
            mapOf("stringValue" to "-Infinity"),
            PostHogLogsOTLP.toAnyValue(Double.NEGATIVE_INFINITY),
        )
    }

    @Test
    fun `toAnyValue null returns null so caller can drop`() {
        assertNull(PostHogLogsOTLP.toAnyValue(null))
    }

    @Test
    fun `toAnyValue List wraps mapped values under arrayValue`() {
        val result = PostHogLogsOTLP.toAnyValue(listOf("a", 1, true))

        @Suppress("UNCHECKED_CAST")
        val values = ((result?.get("arrayValue") as Map<String, Any>)["values"] as List<Map<String, Any>>)
        assertEquals(listOf("a"), values.map { it["stringValue"] }.filterNotNull())
        assertEquals(listOf("1"), values.map { it["intValue"] }.filterNotNull())
        assertEquals(listOf(true), values.map { it["boolValue"] }.filterNotNull())
    }

    @Test
    fun `toAnyValue empty List still produces an empty arrayValue`() {
        // Guard against a refactor that returned null for empty lists — the
        // wire shape should always be arrayValue { values: [] }, not null.
        assertEquals(
            mapOf("arrayValue" to mapOf("values" to emptyList<Any>())),
            PostHogLogsOTLP.toAnyValue(emptyList<Any>()),
        )
    }

    @Test
    fun `toAnyValue nested Map wraps as kvlistValue`() {
        val result = PostHogLogsOTLP.toAnyValue(mapOf("nested" to "yes"))

        @Suppress("UNCHECKED_CAST")
        val kvlist = result?.get("kvlistValue") as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val values = kvlist["values"] as List<Map<String, Any>>
        assertEquals(1, values.size)
        assertEquals("nested", values[0]["key"])
        assertEquals(mapOf("stringValue" to "yes"), values[0]["value"])
    }

    @Test
    fun `toKeyValueList sorts keys alphabetically and drops nulls`() {
        val result =
            PostHogLogsOTLP.toKeyValueList(
                linkedMapOf("z" to "last", "a" to "first", "m" to "mid"),
            )
        assertEquals(listOf("a", "m", "z"), result.map { it["key"] as String })
    }

    @Test
    fun `buildLogRecord produces OTLP shape with body stringValue and severity`() {
        val record =
            PostHogLogRecord(
                body = "hello",
                level = PostHogLogSeverity.WARN,
                timeUnixNano = "100",
                observedTimeUnixNano = "100",
            )
        val out = PostHogLogsOTLP.buildLogRecord(record)
        assertEquals("100", out["timeUnixNano"])
        assertEquals("100", out["observedTimeUnixNano"])
        assertEquals(13, out["severityNumber"])
        assertEquals("warn", out["severityText"])
        assertEquals(mapOf("stringValue" to "hello"), out["body"])
        // Assert key absence (not just value-null) so mutating
        // `record.x?.let { ... }` to always-execute can't silently land
        // null under the key.
        assertFalse(out.containsKey("attributes"))
        assertFalse(out.containsKey("traceId"))
        assertFalse(out.containsKey("spanId"))
        assertFalse(out.containsKey("flags"))
    }

    @Test
    fun `buildLogRecord auto-attaches each context field independently`() {
        // Per-field positive coverage: every record field that auto-attaches
        // to attrs must show up exactly when set and be absent when null.
        // The collision-rules test below covers user-attr-wins-on-conflict.
        val record =
            PostHogLogRecord(
                body = "ctx",
                distinctId = "did-1",
                sessionId = "sess-1",
                screenName = "Home",
                appState = "foreground",
                featureFlagKeys = listOf("flag-a"),
                timeUnixNano = "1",
                observedTimeUnixNano = "1",
            )
        val out = PostHogLogsOTLP.buildLogRecord(record)

        @Suppress("UNCHECKED_CAST")
        val attrs = (out["attributes"] as List<Map<String, Any>>).associateBy { it["key"] as String }

        assertEquals(mapOf("stringValue" to "did-1"), attrs["posthogDistinctId"]?.get("value"))
        assertEquals(mapOf("stringValue" to "sess-1"), attrs["sessionId"]?.get("value"))
        assertEquals(mapOf("stringValue" to "Home"), attrs["screen.name"]?.get("value"))
        assertEquals(mapOf("stringValue" to "foreground"), attrs["app.state"]?.get("value"))
        assertTrue(attrs.containsKey("feature_flags"))
    }

    @Test
    fun `buildLogRecord omits context attribute keys when source fields are null or empty`() {
        // Negative coverage for each auto-attach: with no record context,
        // the corresponding attrs entries must not be present (not just absent
        // in value — actually absent from the KeyValue list).
        val record =
            PostHogLogRecord(
                body = "bare",
                featureFlagKeys = emptyList(),
                timeUnixNano = "1",
                observedTimeUnixNano = "1",
            )
        val out = PostHogLogsOTLP.buildLogRecord(record)

        // With every auto-attach source null/empty and no user attributes,
        // attrs should be omitted entirely.
        assertFalse(out.containsKey("attributes"))
    }

    @Test
    fun `user attributes win on collision for every auto-attached key`() {
        // Every auto-attached key must lose to a user-supplied attribute of the
        // same name. A previous version of this test only exercised the
        // posthogDistinctId collision; a refactor that moved the user merge
        // above only one of the per-field ?.let blocks would have slipped through.
        listOf("posthogDistinctId", "sessionId", "screen.name", "app.state", "feature_flags").forEach { key ->
            val record =
                PostHogLogRecord(
                    body = "x",
                    attributes = mapOf(key to "user-wins"),
                    distinctId = "auto",
                    sessionId = "auto",
                    screenName = "auto",
                    appState = "auto",
                    featureFlagKeys = listOf("auto"),
                    timeUnixNano = "1",
                    observedTimeUnixNano = "1",
                )
            val out = PostHogLogsOTLP.buildLogRecord(record)

            @Suppress("UNCHECKED_CAST")
            val attrs = (out["attributes"] as List<Map<String, Any>>).associateBy { it["key"] as String }
            assertEquals(
                mapOf("stringValue" to "user-wins"),
                attrs[key]?.get("value"),
                "user-supplied $key should win on collision",
            )
        }
    }

    @Test
    fun `buildLogRecord emits traceId spanId and flags when present`() {
        val record =
            PostHogLogRecord(
                body = "trace",
                traceId = "0102030405060708090a0b0c0d0e0f10",
                spanId = "0102030405060708",
                traceFlags = 1,
                timeUnixNano = "1",
                observedTimeUnixNano = "1",
            )
        val out = PostHogLogsOTLP.buildLogRecord(record)
        assertEquals("0102030405060708090a0b0c0d0e0f10", out["traceId"])
        assertEquals("0102030405060708", out["spanId"])
        assertEquals(1, out["flags"])
    }

    @Test
    fun `toAnyValue encodes Date as ISO8601 stringValue`() {
        val epoch = Date(0L)
        val result = PostHogLogsOTLP.toAnyValue(epoch)
        assertEquals(mapOf("stringValue" to "1970-01-01T00:00:00.000Z"), result)
    }

    @Test
    fun `toAnyValue catches non-Long Number subclasses via intValue`() {
        // The `is Number` catch-all is reached by Number subtypes that aren't
        // Long/Int/Short/Byte (e.g. BigInteger, AtomicInteger).
        assertEquals(
            mapOf("intValue" to "98765"),
            PostHogLogsOTLP.toAnyValue(BigInteger("98765")),
        )
    }

    @Test
    fun `toAnyValue Map drops non-String keys and null values`() {
        val mixed: Map<Any?, Any?> =
            mapOf(
                "good" to "yes",
                42 to "non-string-key-dropped",
                "nullValue" to null,
            )
        val result = PostHogLogsOTLP.toAnyValue(mixed)

        @Suppress("UNCHECKED_CAST")
        val values = ((result?.get("kvlistValue") as Map<String, Any>)["values"] as List<Map<String, Any>>)
        val keys = values.map { it["key"] as String }
        assertEquals(listOf("good"), keys)
    }

    @Test
    fun `buildResourceAttributes adds telemetry sdk keys overriding user values`() {
        val merged =
            PostHogLogsOTLP.buildResourceAttributes(
                userResourceAttributes =
                    mapOf(
                        "service.name" to "my-app",
                        "telemetry.sdk.name" to "user-tried-to-shadow",
                    ),
                sdkName = "posthog-android",
                sdkVersion = "1.2.3",
            )
        assertEquals("my-app", merged["service.name"])
        assertEquals("posthog-android", merged["telemetry.sdk.name"])
        assertEquals("1.2.3", merged["telemetry.sdk.version"])
    }

    @Test
    fun `buildPayload nests resourceLogs scopeLogs logRecords`() {
        val record = PostHogLogRecord(body = "p", timeUnixNano = "1", observedTimeUnixNano = "1")
        val payload =
            PostHogLogsOTLP.buildPayload(
                records = listOf(record),
                resourceAttributes = mapOf("service.name" to "app"),
                sdkName = "posthog-android",
                sdkVersion = "1.0",
            )

        @Suppress("UNCHECKED_CAST")
        val resourceLogs = payload["resourceLogs"] as List<Map<String, Any>>
        assertEquals(1, resourceLogs.size)

        @Suppress("UNCHECKED_CAST")
        val resourceAttrs = (resourceLogs[0]["resource"] as Map<String, Any>)["attributes"] as List<Map<String, Any>>
        val attrKeys = resourceAttrs.map { it["key"] as String }
        // Sorted, including telemetry.sdk.* layered on top.
        assertTrue(attrKeys.contains("service.name"))
        assertTrue(attrKeys.contains("telemetry.sdk.name"))
        assertTrue(attrKeys.contains("telemetry.sdk.version"))
        // alphabetical
        assertEquals(attrKeys.sorted(), attrKeys)

        @Suppress("UNCHECKED_CAST")
        val scopeLogs = resourceLogs[0]["scopeLogs"] as List<Map<String, Any>>
        val scope = scopeLogs[0]["scope"] as Map<*, *>
        assertEquals("posthog-android", scope["name"])
        assertEquals("1.0", scope["version"])

        @Suppress("UNCHECKED_CAST")
        val logRecords = scopeLogs[0]["logRecords"] as List<Map<String, Any>>
        assertEquals(1, logRecords.size)
        assertEquals(mapOf("stringValue" to "p"), logRecords[0]["body"])
    }

    @Test
    fun `buildPayload groups multiple records under one resource and scope envelope`() {
        // Architectural invariant: all records in a batch share a single
        // resourceLogs[] entry and a single scopeLogs[] entry. A regression
        // that emitted one resourceLogs per record (or duplicated the resource
        // block per record) would still pass every single-record test.
        val records =
            listOf(
                PostHogLogRecord(body = "one", timeUnixNano = "1", observedTimeUnixNano = "1"),
                PostHogLogRecord(body = "two", timeUnixNano = "2", observedTimeUnixNano = "2"),
                PostHogLogRecord(body = "three", timeUnixNano = "3", observedTimeUnixNano = "3"),
            )
        val payload =
            PostHogLogsOTLP.buildPayload(records, emptyMap(), "posthog-android", "1.0")

        @Suppress("UNCHECKED_CAST")
        val resourceLogs = payload["resourceLogs"] as List<Map<String, Any>>
        assertEquals(1, resourceLogs.size)

        @Suppress("UNCHECKED_CAST")
        val scopeLogs = resourceLogs[0]["scopeLogs"] as List<Map<String, Any>>
        assertEquals(1, scopeLogs.size)

        @Suppress("UNCHECKED_CAST")
        val logRecords = scopeLogs[0]["logRecords"] as List<Map<String, Any>>
        assertEquals(3, logRecords.size)
        assertEquals(
            listOf("one", "two", "three"),
            logRecords.map { (it["body"] as Map<*, *>)["stringValue"] as String },
        )
    }
}
