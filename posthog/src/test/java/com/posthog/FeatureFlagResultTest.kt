package com.posthog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FeatureFlagResultTest {
    // Test helper class for getPayloadAs tests
    data class CustomPayload(val name: String = "", val count: Int = 0)

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
    fun `getPayloadAs converts map payload to requested type`() {
        val payload = mapOf("key" to "value")
        val result = FeatureFlagResult("test-flag", true, null, payload)
        val map = result.getPayloadAs<Map<String, String>>()
        assertEquals("value", map?.get("key"))
    }

    @Test
    fun `getPayloadAs returns null when conversion fails`() {
        val result = FeatureFlagResult("test-flag", true, null, "plain string")
        assertNull(result.getPayloadAs<Map<String, Any>>())
    }

    @Test
    fun `getPayloadAs converts map to custom class`() {
        val payload = mapOf("name" to "test", "count" to 42)
        val result = FeatureFlagResult("test-flag", true, null, payload)
        val custom = result.getPayloadAs<CustomPayload>()
        assertEquals("test", custom?.name)
        assertEquals(42, custom?.count)
    }

    @Test
    fun `getPayloadAs converts list payload`() {
        val payload = listOf("a", "b", "c")
        val result = FeatureFlagResult("test-flag", true, null, payload)
        val list = result.getPayloadAs<List<String>>()
        assertEquals(listOf("a", "b", "c"), list)
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
    fun `getPayloadAs with class converts map to requested type`() {
        val payload = mapOf("key" to "value")
        val result = FeatureFlagResult("test-flag", true, null, payload)
        val map = result.getPayloadAs(Map::class.java)
        assertEquals("value", map?.get("key"))
    }

    @Test
    fun `getPayloadAs with class returns null when conversion fails`() {
        val result = FeatureFlagResult("test-flag", true, null, "plain string")
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
