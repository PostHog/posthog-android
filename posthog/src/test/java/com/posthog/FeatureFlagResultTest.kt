package com.posthog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FeatureFlagResultTest {
    // serializedPayload tests

    @Test
    fun `serializedPayload returns null when payload is null`() {
        val result = FeatureFlagResult("test-flag", true, null, null)
        assertNull(result.serializedPayload())
    }

    @Test
    fun `serializedPayload returns string as-is when payload is string`() {
        val result = FeatureFlagResult("test-flag", true, null, "string payload")
        assertEquals("string payload", result.serializedPayload())
    }

    @Test
    fun `serializedPayload serializes map to JSON`() {
        val payload = mapOf("key" to "value", "number" to 42)
        val result = FeatureFlagResult("test-flag", true, null, payload)
        val serialized = result.serializedPayload()
        assertTrue(serialized?.contains("\"key\":\"value\"") == true)
        assertTrue(serialized?.contains("\"number\":42") == true)
    }

    @Test
    fun `serializedPayload serializes list to JSON`() {
        val payload = listOf("item1", "item2", "item3")
        val result = FeatureFlagResult("test-flag", true, null, payload)
        assertEquals("[\"item1\",\"item2\",\"item3\"]", result.serializedPayload())
    }

    // getPayloadAs<T>() tests

    @Test
    fun `getPayloadAs returns null when payload is null`() {
        val result = FeatureFlagResult("test-flag", true, null, null)
        assertNull(result.getPayloadAs<String>())
        assertNull(result.getPayloadAs<Map<String, Any>>())
    }

    @Test
    fun `getPayloadAs returns payload directly when already correct type`() {
        val payload = mapOf("key" to "value")
        val result = FeatureFlagResult("test-flag", true, null, payload)
        assertEquals(payload, result.getPayloadAs<Map<String, Any>>())
    }

    @Test
    fun `getPayloadAs returns string directly when payload is string`() {
        val result = FeatureFlagResult("test-flag", true, null, "test string")
        assertEquals("test string", result.getPayloadAs<String>())
    }

    @Test
    fun `getPayloadAs deserializes JSON string to requested type`() {
        // When payload is a JSON string, it should be deserialized
        val result = FeatureFlagResult("test-flag", true, null, """{"key":"value"}""")
        val map = result.getPayloadAs<Map<String, String>>()
        assertEquals("value", map?.get("key"))
    }

    @Test
    fun `getPayloadAs returns null when deserialization fails`() {
        val result = FeatureFlagResult("test-flag", true, null, "not valid json for map")
        assertNull(result.getPayloadAs<Map<String, Any>>())
    }

    // getPayloadAs(Class<T>) tests

    @Test
    fun `getPayloadAs with class returns null when payload is null`() {
        val result = FeatureFlagResult("test-flag", true, null, null)
        assertNull(result.getPayloadAs(String::class.java))
        assertNull(result.getPayloadAs(Map::class.java))
    }

    @Test
    fun `getPayloadAs with class returns payload directly when correct type`() {
        val result = FeatureFlagResult("test-flag", true, null, "test string")
        assertEquals("test string", result.getPayloadAs(String::class.java))
    }

    @Test
    fun `getPayloadAs with class deserializes to requested type`() {
        val result = FeatureFlagResult("test-flag", true, null, """{"key":"value"}""")
        val map = result.getPayloadAs(Map::class.java)
        assertEquals("value", map?.get("key"))
    }

    @Test
    fun `getPayloadAs with class returns null when deserialization fails`() {
        val result = FeatureFlagResult("test-flag", true, null, "not valid for Integer")
        assertNull(result.getPayloadAs(Int::class.java))
    }

    // equals tests

    @Test
    fun `equals returns true for same instance`() {
        val result = FeatureFlagResult("test-flag", true, "variant", mapOf("key" to "value"))
        assertEquals(result, result)
    }

    @Test
    fun `equals returns true for identical values`() {
        val result1 = FeatureFlagResult("test-flag", true, "variant", mapOf("key" to "value"))
        val result2 = FeatureFlagResult("test-flag", true, "variant", mapOf("key" to "value"))
        assertEquals(result1, result2)
    }

    @Test
    fun `equals returns false for different keys`() {
        val result1 = FeatureFlagResult("flag1", true, null, null)
        val result2 = FeatureFlagResult("flag2", true, null, null)
        assertNotEquals(result1, result2)
    }

    @Test
    fun `equals returns false for different enabled values`() {
        val result1 = FeatureFlagResult("test-flag", true, null, null)
        val result2 = FeatureFlagResult("test-flag", false, null, null)
        assertNotEquals(result1, result2)
    }

    @Test
    fun `equals returns false for different variants`() {
        val result1 = FeatureFlagResult("test-flag", true, "variant1", null)
        val result2 = FeatureFlagResult("test-flag", true, "variant2", null)
        assertNotEquals(result1, result2)
    }

    @Test
    fun `equals returns false for different payloads`() {
        val result1 = FeatureFlagResult("test-flag", true, null, "payload1")
        val result2 = FeatureFlagResult("test-flag", true, null, "payload2")
        assertNotEquals(result1, result2)
    }

    @Test
    fun `equals returns false for null comparison`() {
        val result = FeatureFlagResult("test-flag", true, null, null)
        assertNotEquals<FeatureFlagResult?>(result, null)
    }

    @Test
    fun `equals returns false for different type`() {
        val result = FeatureFlagResult("test-flag", true, null, null)
        assertNotEquals(result as Any, "not a FeatureFlagResult")
    }

    // hashCode tests

    @Test
    fun `hashCode is consistent for same values`() {
        val result1 = FeatureFlagResult("test-flag", true, "variant", mapOf("key" to "value"))
        val result2 = FeatureFlagResult("test-flag", true, "variant", mapOf("key" to "value"))
        assertEquals(result1.hashCode(), result2.hashCode())
    }

    @Test
    fun `hashCode differs for different values`() {
        val result1 = FeatureFlagResult("flag1", true, null, null)
        val result2 = FeatureFlagResult("flag2", true, null, null)
        // While hashCode collisions are possible, these should be different
        assertNotEquals(result1.hashCode(), result2.hashCode())
    }

    // toString tests

    @Test
    fun `toString contains all properties`() {
        val result = FeatureFlagResult("test-flag", true, "variant", "payload")
        val str = result.toString()
        assertTrue(str.contains("key='test-flag'"))
        assertTrue(str.contains("enabled=true"))
        assertTrue(str.contains("variant=variant"))
        assertTrue(str.contains("payload=payload"))
    }

    @Test
    fun `toString handles null values`() {
        val result = FeatureFlagResult("test-flag", false, null, null)
        val str = result.toString()
        assertTrue(str.contains("key='test-flag'"))
        assertTrue(str.contains("enabled=false"))
        assertTrue(str.contains("variant=null"))
        assertTrue(str.contains("payload=null"))
    }

    // Feature flag result creation tests

    @Test
    fun `boolean flag creates correct result`() {
        val result = FeatureFlagResult("boolean-flag", true, null, null)
        assertEquals("boolean-flag", result.key)
        assertTrue(result.enabled)
        assertNull(result.variant)
        assertNull(result.payload)
    }

    @Test
    fun `variant flag creates correct result`() {
        val result = FeatureFlagResult("variant-flag", true, "test-variant", null)
        assertEquals("variant-flag", result.key)
        assertTrue(result.enabled)
        assertEquals("test-variant", result.variant)
        assertNull(result.payload)
    }

    @Test
    fun `flag with payload creates correct result`() {
        val payload = mapOf("discount" to 10, "features" to listOf("a", "b"))
        val result = FeatureFlagResult("payload-flag", true, "premium", payload)
        assertEquals("payload-flag", result.key)
        assertTrue(result.enabled)
        assertEquals("premium", result.variant)
        assertEquals(payload, result.payload)
    }
}
