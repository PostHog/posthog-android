package com.posthog.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class PostHogFeatureFlagOptionsTest {
    @Test
    fun `builder creates instance with all null properties by default`() {
        val options = PostHogFeatureFlagOptions.builder().build()

        assertNull(options.defaultValue)
        assertNull(options.groups)
        assertNull(options.personProperties)
        assertNull(options.groupProperties)
    }

    @Test
    fun `defaultValue method sets default value`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .defaultValue("default")
                .build()

        assertEquals("default", options.defaultValue)
    }

    @Test
    fun `defaultValue method handles different value types`() {
        val stringOptions =
            PostHogFeatureFlagOptions.builder()
                .defaultValue("string_value")
                .build()

        val booleanOptions =
            PostHogFeatureFlagOptions.builder()
                .defaultValue(true)
                .build()

        val intOptions =
            PostHogFeatureFlagOptions.builder()
                .defaultValue(42)
                .build()

        assertEquals("string_value", stringOptions.defaultValue)
        assertEquals(true, booleanOptions.defaultValue)
        assertEquals(42, intOptions.defaultValue)
    }

    @Test
    fun `defaultValue method returns builder for chaining`() {
        val builder = PostHogFeatureFlagOptions.builder()
        val result = builder.defaultValue("test")

        assertEquals(builder, result)
    }

    @Test
    fun `group method adds single group`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .group("organization", "org_123")
                .build()

        assertEquals(mapOf("organization" to "org_123"), options.groups)
    }

    @Test
    fun `group method creates groups map when null`() {
        val builder = PostHogFeatureFlagOptions.builder()
        assertNull(builder.groups)

        builder.group("organization", "org_123")

        assertEquals(mutableMapOf<String, String>("organization" to "org_123"), builder.groups)
    }

    @Test
    fun `group method adds to existing groups map`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .group("organization", "org_123")
                .group("team", "team_456")
                .build()

        assertEquals(mapOf("organization" to "org_123", "team" to "team_456"), options.groups)
    }

    @Test
    fun `group method returns builder for chaining`() {
        val builder = PostHogFeatureFlagOptions.builder()
        val result = builder.group("organization", "org_123")

        assertEquals(builder, result)
    }

    @Test
    fun `groups method adds multiple groups`() {
        val groupsToAdd = mapOf("organization" to "org_123", "team" to "team_456")
        val options =
            PostHogFeatureFlagOptions.builder()
                .groups(groupsToAdd)
                .build()

        assertEquals(groupsToAdd, options.groups)
    }

    @Test
    fun `groups method creates groups map when null`() {
        val builder = PostHogFeatureFlagOptions.builder()
        assertNull(builder.groups)

        val groupsToAdd = mapOf("organization" to "org_123")
        builder.groups(groupsToAdd)

        assertEquals(mutableMapOf<String, String>("organization" to "org_123"), builder.groups)
    }

    @Test
    fun `groups method appends to existing groups`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .group("existing_type", "existing_key")
                .groups(mapOf("new_type1" to "new_key1", "new_type2" to "new_key2"))
                .build()

        val expected =
            mapOf(
                "existing_type" to "existing_key",
                "new_type1" to "new_key1",
                "new_type2" to "new_key2",
            )
        assertEquals(expected, options.groups)
    }

    @Test
    fun `groups method returns builder for chaining`() {
        val builder = PostHogFeatureFlagOptions.builder()
        val result = builder.groups(mapOf("organization" to "org_123"))

        assertEquals(builder, result)
    }

    @Test
    fun `personProperty method adds single person property`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .personProperty("plan", "premium")
                .build()

        assertEquals(mapOf("plan" to "premium"), options.personProperties)
    }

    @Test
    fun `personProperty method creates personProperties map when null`() {
        val builder = PostHogFeatureFlagOptions.builder()
        assertNull(builder.personProperties)

        builder.personProperty("plan", "premium")

        assertEquals(mutableMapOf<String, Any?>("plan" to "premium"), builder.personProperties)
    }

    @Test
    fun `personProperty method adds to existing personProperties map`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .personProperty("plan", "premium")
                .personProperty("role", "admin")
                .build()

        assertEquals(mapOf("plan" to "premium", "role" to "admin"), options.personProperties)
    }

    @Test
    fun `personProperty method returns builder for chaining`() {
        val builder = PostHogFeatureFlagOptions.builder()
        val result = builder.personProperty("plan", "premium")

        assertEquals(builder, result)
    }

    @Test
    fun `personProperties method adds multiple person properties`() {
        val propertiesToAdd = mapOf("plan" to "premium", "role" to "admin")
        val options =
            PostHogFeatureFlagOptions.builder()
                .personProperties(propertiesToAdd)
                .build()

        assertEquals(propertiesToAdd, options.personProperties)
    }

    @Test
    fun `personProperties method creates personProperties map when null`() {
        val builder = PostHogFeatureFlagOptions.builder()
        assertNull(builder.personProperties)

        val propertiesToAdd = mapOf("plan" to "premium")
        builder.personProperties(propertiesToAdd)

        assertEquals(mutableMapOf<String, Any?>("plan" to "premium"), builder.personProperties)
    }

    @Test
    fun `personProperties method appends to existing personProperties`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .personProperty("existing_key", "existing_value")
                .personProperties(mapOf("new_key1" to "new_value1", "new_key2" to "new_value2"))
                .build()

        val expected =
            mapOf(
                "existing_key" to "existing_value",
                "new_key1" to "new_value1",
                "new_key2" to "new_value2",
            )
        assertEquals(expected, options.personProperties)
    }

    @Test
    fun `personProperties method returns builder for chaining`() {
        val builder = PostHogFeatureFlagOptions.builder()
        val result = builder.personProperties(mapOf("plan" to "premium"))

        assertEquals(builder, result)
    }

    @Test
    fun `groupProperty method adds single group property`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .groupProperty("my-org", "industry", "tech")
                .build()

        assertEquals(mapOf("my-org" to mapOf("industry" to "tech")), options.groupProperties)
    }

    @Test
    fun `groupProperty method creates groupProperties map when null`() {
        val builder = PostHogFeatureFlagOptions.builder()
        assertNull(builder.groupProperties)

        builder.groupProperty("my-org", "industry", "tech")

        assertEquals(mutableMapOf("my-org" to mutableMapOf<String, Any?>("industry" to "tech")), builder.groupProperties)
    }

    @Test
    fun `groupProperty method adds to existing groupProperties map`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .groupProperty("my-org", "industry", "tech")
                .groupProperty("my-org", "size", "large")
                .build()

        assertEquals(mapOf("my-org" to mapOf("industry" to "tech", "size" to "large")), options.groupProperties)
    }

    @Test
    fun `groupProperty method returns builder for chaining`() {
        val builder = PostHogFeatureFlagOptions.builder()
        val result = builder.groupProperty("my-org", "industry", "tech")

        assertEquals(builder, result)
    }

    @Test
    fun `groupProperties method adds multiple group properties`() {
        val propertiesToAdd = mapOf("my-org" to mapOf("industry" to "tech", "size" to "large"))
        val options =
            PostHogFeatureFlagOptions.builder()
                .groupProperties(propertiesToAdd)
                .build()

        assertEquals(propertiesToAdd, options.groupProperties)
    }

    @Test
    fun `groupProperties method creates groupProperties map when null`() {
        val builder = PostHogFeatureFlagOptions.builder()
        assertNull(builder.groupProperties)

        val propertiesToAdd = mapOf("my-org" to mapOf("industry" to "tech"))
        builder.groupProperties(propertiesToAdd)

        assertEquals(mutableMapOf("my-org" to mutableMapOf<String, Any?>("industry" to "tech")), builder.groupProperties)
    }

    @Test
    fun `groupProperties method appends to existing groupProperties`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .groupProperty("my-org", "existing_key", "existing_value")
                .groupProperties(mapOf("my-org" to mapOf("new_key1" to "new_value1", "new_key2" to "new_value2")))
                .build()

        val expected =
            mapOf(
                "my-org" to
                    mapOf(
                        "existing_key" to "existing_value",
                        "new_key1" to "new_value1",
                        "new_key2" to "new_value2",
                    ),
            )
        assertEquals(expected, options.groupProperties)
    }

    @Test
    fun `groupProperties method returns builder for chaining`() {
        val builder = PostHogFeatureFlagOptions.builder()
        val result = builder.groupProperties(mapOf("my-org" to mapOf("industry" to "tech")))

        assertEquals(builder, result)
    }

    @Test
    fun `builder allows full chaining of all methods`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .defaultValue("default")
                .group("organization", "org_123")
                .groups(mapOf("team" to "team_456"))
                .personProperty("plan", "premium")
                .personProperties(mapOf("role" to "admin"))
                .groupProperty("my-org", "industry", "tech")
                .groupProperties(mapOf("my-org" to mapOf("size" to "large")))
                .build()

        assertEquals("default", options.defaultValue)
        assertEquals(mapOf("organization" to "org_123", "team" to "team_456"), options.groups)
        assertEquals(mapOf("plan" to "premium", "role" to "admin"), options.personProperties)
        assertEquals(mapOf("my-org" to mapOf("industry" to "tech", "size" to "large")), options.groupProperties)
    }

    @Test
    fun `overwriting same key in groups replaces value`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .group("organization", "org_123")
                .group("organization", "org_456")
                .build()

        assertEquals(mapOf("organization" to "org_456"), options.groups)
    }

    @Test
    fun `overwriting same key in personProperties replaces value`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .personProperty("plan", "free")
                .personProperty("plan", "premium")
                .build()

        assertEquals(mapOf("plan" to "premium"), options.personProperties)
    }

    @Test
    fun `overwriting same key in groupProperties replaces value`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .groupProperty("my-org", "size", "small")
                .groupProperty("my-org", "size", "large")
                .build()

        assertEquals(mapOf("my-org" to mapOf("size" to "large")), options.groupProperties)
    }

    @Test
    fun `empty maps in properties methods work correctly`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .groups(emptyMap())
                .personProperties(emptyMap())
                .groupProperties(emptyMap())
                .build()

        assertEquals(emptyMap(), options.groups)
        assertEquals(emptyMap(), options.personProperties)
        assertEquals(emptyMap(), options.groupProperties)
    }

    @Test
    fun `maps passed to properties methods are correctly copied`() {
        val originalGroups = mutableMapOf("organization" to "org_123")
        val options =
            PostHogFeatureFlagOptions.builder()
                .groups(originalGroups)
                .build()

        // Modify original map
        originalGroups["new_type"] = "new_key"

        // Built options should not be affected
        assertEquals(mapOf("organization" to "org_123"), options.groups)
    }

    @Test
    fun `overwriting defaultValue replaces value`() {
        val options =
            PostHogFeatureFlagOptions.builder()
                .defaultValue("first")
                .defaultValue("second")
                .build()

        assertEquals("second", options.defaultValue)
    }
}
