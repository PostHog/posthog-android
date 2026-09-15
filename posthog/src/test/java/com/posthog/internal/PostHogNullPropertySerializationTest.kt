package com.posthog.internal

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogNullPropertySerializationTest {
    private val serializer = PostHogConfig("null-properties-test", "http://127.0.0.1").serializer

    @Test
    fun `event and batch omit null members without compacting Java arrays or mutating input`() {
        val properties = NullPropertyInputs.properties()
        val original = serializer.gson.toJsonTree(properties).deepCopy()
        val event = PostHogEvent("Nullable", "user", properties)
        val encoded = StringWriter()
        serializer.serialize(event, encoded)
        assertEquals(expectedProperties(), JsonParser.parseString(encoded.toString()).asJsonObject["properties"])

        val batch = StringWriter()
        serializer.serialize(PostHogBatchEvent("null-properties-test", listOf(event)), batch)
        val batchEvent = JsonParser.parseString(batch.toString()).asJsonObject.getAsJsonArray("batch")[0].asJsonObject
        assertEquals(expectedProperties(), batchEvent["properties"])
        assertEquals(original, serializer.gson.toJsonTree(properties))
        assertTrue(properties.containsKey("test"))
        assertNull(properties["test"])
    }

    @Test
    fun `nullable Kotlin containers preserve every null position`() {
        val properties =
            mutableMapOf<String, Any>(
                "items" to listOf(null, mapOf("drop" to null), null, arrayOf<Any?>(null, false, 0, ""), null),
            )
        val encoded = StringWriter()
        serializer.serialize(PostHogEvent("Nullable", "user", properties), encoded)
        val actual = JsonParser.parseString(encoded.toString()).asJsonObject["properties"]
        assertEquals(JsonParser.parseString("""{"items":[null,{},null,[null,false,0,""],null]}"""), actual)
    }

    @Test
    fun `null-only property object and absent typed metadata retain existing semantics`() {
        val properties = NullPropertyInputs.properties().apply { keys.retainAll(setOf("test")) }
        val encoded = StringWriter()
        serializer.serialize(PostHogEvent("OnlyNull", "user", properties), encoded)
        val actual = JsonParser.parseString(encoded.toString()).asJsonObject
        assertEquals(JsonObject(), actual["properties"])
        assertEquals("OnlyNull", actual["event"].asString)
        assertFalse(actual.has("api_key"))
        assertFalse(actual.has("message_id"))
    }

    @Test
    fun `Gson tree and object conversions omit null members and preserve array positions`() {
        class CustomProperty(val items: List<Any?>, val absent: String? = null)

        val properties =
            mutableMapOf<String, Any>(
                "tree" to JsonParser.parseString("""{"drop":null,"items":[null,{"drop":null}]}"""),
                "object" to CustomProperty(listOf(null, mapOf("drop" to null))),
            )
        val encoded = StringWriter()
        serializer.serialize(PostHogEvent("Converted", "user", properties), encoded)
        assertEquals(
            JsonParser.parseString("""{"tree":{"items":[null,{}]},"object":{"items":[null,{}]}}"""),
            JsonParser.parseString(encoded.toString()).asJsonObject["properties"],
        )
    }

    @Test
    fun `event mode reaches maps inside reflected custom properties`() {
        class CustomProperty(val nested: Map<String, Any?>)

        val nested = mapOf("drop" to null, "items" to listOf(null, "x", mapOf("drop" to null)))
        val properties = mutableMapOf<String, Any>("object" to CustomProperty(nested))
        val encoded = StringWriter()
        serializer.serialize(PostHogEvent("Converted", "user", properties), encoded)
        assertEquals(
            JsonParser.parseString("""{"object":{"nested":{"items":[null,"x",{}]}}}"""),
            JsonParser.parseString(encoded.toString()).asJsonObject["properties"],
        )
        val general = StringWriter()
        serializer.serialize(properties, general)
        assertEquals(
            JsonParser.parseString("""{"object":{"nested":{"items":["x",{}]}}}"""),
            JsonParser.parseString(general.toString()),
        )
    }

    @Test
    fun `unsupported list elements are still dropped rather than replaced with null`() {
        val properties = mutableMapOf<String, Any>("items" to listOf(null, Thread.currentThread(), "good", null))
        val encoded = StringWriter()
        serializer.serialize(PostHogEvent("Unsupported", "user", properties), encoded)
        assertEquals(
            JsonParser.parseString("""{"items":[null,"good",null]}"""),
            JsonParser.parseString(encoded.toString()).asJsonObject["properties"],
        )
    }

    @Test
    fun `event adapter does not change general map serialization before or after encoding`() {
        val properties = mutableMapOf<String, Any>("items" to listOf(null, "x", mapOf("drop" to null)))
        repeat(2) {
            val mutableMap = StringWriter()
            serializer.serialize(properties, mutableMap)
            assertEquals(JsonParser.parseString("""{"items":["x",{}]}"""), JsonParser.parseString(mutableMap.toString()))
            val map = StringWriter()
            serializer.serialize<Map<String, Any>>(properties, map)
            assertEquals(mutableMap.toString(), map.toString())

            val event = StringWriter()
            serializer.serialize(PostHogEvent("Nullable", "user", properties), event)
            assertEquals(
                JsonParser.parseString("""{"items":[null,"x",{}]}"""),
                JsonParser.parseString(event.toString()).asJsonObject["properties"],
            )
        }
    }

    private fun expectedProperties() =
        JsonParser.parseString(
            """
            {"nested":{},"items":["1",null,2,{},[null]],"array":[null,{}],"${'$'}set":{},"${'$'}group_set":{},
             "empty":"","zero":0,"enabled":false,"literal":"null","literalUndefined":"undefined","emptyArray":[]}
            """.trimIndent(),
        )
}
