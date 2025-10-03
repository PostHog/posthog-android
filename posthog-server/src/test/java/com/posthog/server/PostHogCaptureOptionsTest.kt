package com.posthog.server

import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class PostHogCaptureOptionsTest {
    @Test
    fun `builder creates instance with all null properties by default`() {
        val options = PostHogCaptureOptions.builder().build()

        assertNull(options.properties)
        assertNull(options.userProperties)
        assertNull(options.userPropertiesSetOnce)
        assertNull(options.groups)
    }

    @Test
    fun `property method adds single property`() {
        val options =
            PostHogCaptureOptions.builder()
                .property("test_key", "test_value")
                .build()

        assertEquals(mapOf("test_key" to "test_value"), options.properties)
    }

    @Test
    fun `property method creates properties map when null`() {
        val builder = PostHogCaptureOptions.builder()
        assertNull(builder.properties)

        builder.property("test_key", "test_value")

        assertEquals(mutableMapOf<String, Any>("test_key" to "test_value"), builder.properties)
    }

    @Test
    fun `property method adds to existing properties map`() {
        val options =
            PostHogCaptureOptions.builder()
                .property("key1", "value1")
                .property("key2", "value2")
                .build()

        assertEquals(mapOf("key1" to "value1", "key2" to "value2"), options.properties)
    }

    @Test
    fun `property method returns builder for chaining`() {
        val builder = PostHogCaptureOptions.builder()
        val result = builder.property("test_key", "test_value")

        assertEquals(builder, result)
    }

    @Test
    fun `property method handles different value types`() {
        val options =
            PostHogCaptureOptions.builder()
                .property("string_key", "string_value")
                .property("int_key", 42)
                .property("boolean_key", true)
                .property("double_key", 3.14)
                .build()

        val expected =
            mapOf(
                "string_key" to "string_value",
                "int_key" to 42,
                "boolean_key" to true,
                "double_key" to 3.14,
            )
        assertEquals(expected, options.properties)
    }

    @Test
    fun `properties method adds multiple properties`() {
        val propertiesToAdd = mapOf("key1" to "value1", "key2" to "value2")
        val options =
            PostHogCaptureOptions.builder()
                .properties(propertiesToAdd)
                .build()

        assertEquals(propertiesToAdd, options.properties)
    }

    @Test
    fun `properties method creates properties map when null`() {
        val builder = PostHogCaptureOptions.builder()
        assertNull(builder.properties)

        val propertiesToAdd = mapOf("key1" to "value1")
        builder.properties(propertiesToAdd)

        assertEquals(mutableMapOf<String, Any>("key1" to "value1"), builder.properties)
    }

    @Test
    fun `properties method appends to existing properties`() {
        val options =
            PostHogCaptureOptions.builder()
                .property("existing_key", "existing_value")
                .properties(mapOf("new_key1" to "new_value1", "new_key2" to "new_value2"))
                .build()

        val expected =
            mapOf(
                "existing_key" to "existing_value",
                "new_key1" to "new_value1",
                "new_key2" to "new_value2",
            )
        assertEquals(expected, options.properties)
    }

    @Test
    fun `properties method returns builder for chaining`() {
        val builder = PostHogCaptureOptions.builder()
        val result = builder.properties(mapOf("key" to "value"))

        assertEquals(builder, result)
    }

    @Test
    fun `userProperty method adds single user property`() {
        val options =
            PostHogCaptureOptions.builder()
                .userProperty("user_key", "user_value")
                .build()

        assertEquals(mapOf("user_key" to "user_value"), options.userProperties)
    }

    @Test
    fun `userProperty method creates userProperties map when null`() {
        val builder = PostHogCaptureOptions.builder()
        assertNull(builder.userProperties)

        builder.userProperty("user_key", "user_value")

        assertEquals(mutableMapOf<String, Any>("user_key" to "user_value"), builder.userProperties)
    }

    @Test
    fun `userProperty method adds to existing userProperties map`() {
        val options =
            PostHogCaptureOptions.builder()
                .userProperty("key1", "value1")
                .userProperty("key2", "value2")
                .build()

        assertEquals(mapOf("key1" to "value1", "key2" to "value2"), options.userProperties)
    }

    @Test
    fun `userProperty method returns builder for chaining`() {
        val builder = PostHogCaptureOptions.builder()
        val result = builder.userProperty("user_key", "user_value")

        assertEquals(builder, result)
    }

    @Test
    fun `userProperties method adds multiple user properties`() {
        val userPropertiesToAdd = mapOf("key1" to "value1", "key2" to "value2")
        val options =
            PostHogCaptureOptions.builder()
                .userProperties(userPropertiesToAdd)
                .build()

        assertEquals(userPropertiesToAdd, options.userProperties)
    }

    @Test
    fun `userProperties method creates userProperties map when null`() {
        val builder = PostHogCaptureOptions.builder()
        assertNull(builder.userProperties)

        val userPropertiesToAdd = mapOf("key1" to "value1")
        builder.userProperties(userPropertiesToAdd)

        assertEquals(mutableMapOf<String, Any>("key1" to "value1"), builder.userProperties)
    }

    @Test
    fun `userProperties method appends to existing userProperties`() {
        val options =
            PostHogCaptureOptions.builder()
                .userProperty("existing_key", "existing_value")
                .userProperties(mapOf("new_key1" to "new_value1", "new_key2" to "new_value2"))
                .build()

        val expected =
            mapOf(
                "existing_key" to "existing_value",
                "new_key1" to "new_value1",
                "new_key2" to "new_value2",
            )
        assertEquals(expected, options.userProperties)
    }

    @Test
    fun `userProperties method returns builder for chaining`() {
        val builder = PostHogCaptureOptions.builder()
        val result = builder.userProperties(mapOf("key" to "value"))

        assertEquals(builder, result)
    }

    @Test
    fun `userPropertySetOnce method adds single user property set once`() {
        val options =
            PostHogCaptureOptions.builder()
                .userPropertySetOnce("once_key", "once_value")
                .build()

        assertEquals(mapOf("once_key" to "once_value"), options.userPropertiesSetOnce)
    }

    @Test
    fun `userPropertySetOnce method creates userPropertiesSetOnce map when null`() {
        val builder = PostHogCaptureOptions.builder()
        assertNull(builder.userPropertiesSetOnce)

        builder.userPropertySetOnce("once_key", "once_value")

        assertEquals(
            mutableMapOf<String, Any>("once_key" to "once_value"),
            builder.userPropertiesSetOnce,
        )
    }

    @Test
    fun `userPropertySetOnce method adds to existing userPropertiesSetOnce map`() {
        val options =
            PostHogCaptureOptions.builder()
                .userPropertySetOnce("key1", "value1")
                .userPropertySetOnce("key2", "value2")
                .build()

        assertEquals(mapOf("key1" to "value1", "key2" to "value2"), options.userPropertiesSetOnce)
    }

    @Test
    fun `userPropertySetOnce method returns builder for chaining`() {
        val builder = PostHogCaptureOptions.builder()
        val result = builder.userPropertySetOnce("once_key", "once_value")

        assertEquals(builder, result)
    }

    @Test
    fun `userPropertiesSetOnce method adds multiple user properties set once`() {
        val userPropertiesSetOnceToAdd = mapOf("key1" to "value1", "key2" to "value2")
        val options =
            PostHogCaptureOptions.builder()
                .userPropertiesSetOnce(userPropertiesSetOnceToAdd)
                .build()

        assertEquals(userPropertiesSetOnceToAdd, options.userPropertiesSetOnce)
    }

    @Test
    fun `userPropertiesSetOnce method creates userPropertiesSetOnce map when null`() {
        val builder = PostHogCaptureOptions.builder()
        assertNull(builder.userPropertiesSetOnce)

        val userPropertiesSetOnceToAdd = mapOf("key1" to "value1")
        builder.userPropertiesSetOnce(userPropertiesSetOnceToAdd)

        assertEquals(mutableMapOf<String, Any>("key1" to "value1"), builder.userPropertiesSetOnce)
    }

    @Test
    fun `userPropertiesSetOnce method appends to existing userPropertiesSetOnce`() {
        val options =
            PostHogCaptureOptions.builder()
                .userPropertySetOnce("existing_key", "existing_value")
                .userPropertiesSetOnce(
                    mapOf(
                        "new_key1" to "new_value1",
                        "new_key2" to "new_value2",
                    ),
                )
                .build()

        val expected =
            mapOf(
                "existing_key" to "existing_value",
                "new_key1" to "new_value1",
                "new_key2" to "new_value2",
            )
        assertEquals(expected, options.userPropertiesSetOnce)
    }

    @Test
    fun `userPropertiesSetOnce method returns builder for chaining`() {
        val builder = PostHogCaptureOptions.builder()
        val result = builder.userPropertiesSetOnce(mapOf("key" to "value"))

        assertEquals(builder, result)
    }

    @Test
    fun `group method adds single group`() {
        val options =
            PostHogCaptureOptions.builder()
                .group("organization", "org_123")
                .build()

        assertEquals(mapOf("organization" to "org_123"), options.groups)
    }

    @Test
    fun `group method creates groups map when null`() {
        val builder = PostHogCaptureOptions.builder()
        assertNull(builder.groups)

        builder.group("organization", "org_123")

        assertEquals(mutableMapOf<String, String>("organization" to "org_123"), builder.groups)
    }

    @Test
    fun `group method adds to existing groups map`() {
        val options =
            PostHogCaptureOptions.builder()
                .group("organization", "org_123")
                .group("team", "team_456")
                .build()

        assertEquals(mapOf("organization" to "org_123", "team" to "team_456"), options.groups)
    }

    @Test
    fun `group method returns builder for chaining`() {
        val builder = PostHogCaptureOptions.builder()
        val result = builder.group("organization", "org_123")

        assertEquals(builder, result)
    }

    @Test
    fun `groups method adds multiple groups`() {
        val groupsToAdd = mapOf("organization" to "org_123", "team" to "team_456")
        val options =
            PostHogCaptureOptions.builder()
                .groups(groupsToAdd)
                .build()

        assertEquals(groupsToAdd, options.groups)
    }

    @Test
    fun `groups method creates groups map when null`() {
        val builder = PostHogCaptureOptions.builder()
        assertNull(builder.groups)

        val groupsToAdd = mapOf("organization" to "org_123")
        builder.groups(groupsToAdd)

        assertEquals(mutableMapOf<String, String>("organization" to "org_123"), builder.groups)
    }

    @Test
    fun `groups method appends to existing groups`() {
        val options =
            PostHogCaptureOptions.builder()
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
        val builder = PostHogCaptureOptions.builder()
        val result = builder.groups(mapOf("organization" to "org_123"))

        assertEquals(builder, result)
    }

    @Test
    fun `builder allows full chaining of all methods`() {
        val options =
            PostHogCaptureOptions.builder()
                .property("prop_key", "prop_value")
                .properties(mapOf("prop_key2" to "prop_value2"))
                .userProperty("user_key", "user_value")
                .userProperties(mapOf("user_key2" to "user_value2"))
                .userPropertySetOnce("once_key", "once_value")
                .userPropertiesSetOnce(mapOf("once_key2" to "once_value2"))
                .group("organization", "org_123")
                .groups(mapOf("team" to "team_456"))
                .build()

        assertEquals(
            mapOf("prop_key" to "prop_value", "prop_key2" to "prop_value2"),
            options.properties,
        )
        assertEquals(
            mapOf("user_key" to "user_value", "user_key2" to "user_value2"),
            options.userProperties,
        )
        assertEquals(
            mapOf("once_key" to "once_value", "once_key2" to "once_value2"),
            options.userPropertiesSetOnce,
        )
        assertEquals(mapOf("organization" to "org_123", "team" to "team_456"), options.groups)
    }

    @Test
    fun `overwriting same key in properties replaces value`() {
        val options =
            PostHogCaptureOptions.builder()
                .property("key", "first_value")
                .property("key", "second_value")
                .build()

        assertEquals(mapOf("key" to "second_value"), options.properties)
    }

    @Test
    fun `overwriting same key in userProperties replaces value`() {
        val options =
            PostHogCaptureOptions.builder()
                .userProperty("key", "first_value")
                .userProperty("key", "second_value")
                .build()

        assertEquals(mapOf("key" to "second_value"), options.userProperties)
    }

    @Test
    fun `overwriting same key in userPropertiesSetOnce replaces value`() {
        val options =
            PostHogCaptureOptions.builder()
                .userPropertySetOnce("key", "first_value")
                .userPropertySetOnce("key", "second_value")
                .build()

        assertEquals(mapOf("key" to "second_value"), options.userPropertiesSetOnce)
    }

    @Test
    fun `overwriting same key in groups replaces value`() {
        val options =
            PostHogCaptureOptions.builder()
                .group("organization", "org_123")
                .group("organization", "org_456")
                .build()

        assertEquals(mapOf("organization" to "org_456"), options.groups)
    }

    @Test
    fun `empty maps in properties methods work correctly`() {
        val options =
            PostHogCaptureOptions.builder()
                .properties(emptyMap())
                .userProperties(emptyMap())
                .userPropertiesSetOnce(emptyMap())
                .groups(emptyMap())
                .build()

        assertEquals(emptyMap(), options.properties)
        assertEquals(emptyMap(), options.userProperties)
        assertEquals(emptyMap(), options.userPropertiesSetOnce)
        assertEquals(emptyMap(), options.groups)
    }

    @Test
    fun `maps passed to properties methods are correctly copied`() {
        val originalProperties = mutableMapOf("key" to "value")
        val options =
            PostHogCaptureOptions.builder()
                .properties(originalProperties)
                .build()

        // Modify original map
        originalProperties["new_key"] = "new_value"

        // Built options should not be affected
        assertEquals(mapOf("key" to "value"), options.properties)
    }

    @Test
    fun `timestamp with Date sets timestamp correctly`() {
        val date = Date(1234567890L)
        val options =
            PostHogCaptureOptions.builder()
                .timestamp(date)
                .build()

        assertEquals(date, options.timestamp)
    }

    @Test
    fun `timestamp with Long converts epoch millis to Date`() {
        val epochMillis = 1234567890L
        val options =
            PostHogCaptureOptions.builder()
                .timestamp(epochMillis)
                .build()

        assertEquals(Date(epochMillis), options.timestamp)
    }

    @Test
    fun `timestamp with Instant converts Instant to Date`() {
        val instant = Instant.ofEpochMilli(1234567890L)
        val options =
            PostHogCaptureOptions.builder()
                .timestamp(instant)
                .build()

        assertEquals(Date(1234567890L), options.timestamp)
    }

    @Test
    fun `timestamp defaults to null when not set`() {
        val options = PostHogCaptureOptions.builder().build()

        assertNull(options.timestamp)
    }

    @Test
    fun `timestamp returns builder for chaining`() {
        val builder = PostHogCaptureOptions.builder()
        val date = Date()
        val result = builder.timestamp(date)

        assertEquals(builder, result)
    }

    @Test
    fun `overwriting timestamp replaces value`() {
        val firstDate = Date(1234567890L)
        val secondDate = Date(1640995200000L)
        val options =
            PostHogCaptureOptions.builder()
                .timestamp(firstDate)
                .timestamp(secondDate)
                .build()

        assertEquals(secondDate, options.timestamp)
    }

    @Test
    fun `timestamp with different formats all work correctly`() {
        val epochMillis = 1234567890L
        val instant = Instant.ofEpochMilli(epochMillis)
        val date = Date(epochMillis)

        val optionsFromDate =
            PostHogCaptureOptions.builder()
                .timestamp(date)
                .build()

        val optionsFromLong =
            PostHogCaptureOptions.builder()
                .timestamp(epochMillis)
                .build()

        val optionsFromInstant =
            PostHogCaptureOptions.builder()
                .timestamp(instant)
                .build()

        // All should produce the same timestamp
        assertEquals(date, optionsFromDate.timestamp)
        assertEquals(date, optionsFromLong.timestamp)
        assertEquals(date, optionsFromInstant.timestamp)
    }
}
