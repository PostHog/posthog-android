package com.posthog.internal

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

public class PostHogLocalEvaluationModelsTest {
    private val gson =
        GsonBuilder()
            .registerTypeAdapter(PropertyGroup::class.java, PropertyGroupDeserializer())
            .registerTypeAdapter(PropertyValue::class.java, PropertyValueDeserializer())
            .create()

    @Test
    public fun testPropertyGroupParseWithFlagProperties() {
        val json =
            """
            {
              "type": "AND",
              "values": [
                {
                  "key": "email",
                  "operator": "exact",
                  "value": "test@example.com"
                }
              ]
            }
            """.trimIndent()

        val expected =
            PropertyGroup(
                type = LogicalOperator.AND,
                values =
                    PropertyValue.FlagProperties(
                        listOf(
                            FlagProperty(
                                key = "email",
                                propertyValue = "test@example.com",
                                propertyOperator = PropertyOperator.EXACT,
                                type = null,
                                negation = null,
                                dependencyChain = null,
                            ),
                        ),
                    ),
            )

        assertEquals(expected, gson.fromJson(json, PropertyGroup::class.java))
    }

    @Test
    public fun testPropertyGroupParseWithNestedPropertyGroups() {
        val json =
            """
            {
              "type": "OR",
              "values": [
                {
                  "type": "AND",
                  "values": [
                    {
                      "key": "email",
                      "operator": "icontains",
                      "value": "example.com"
                    }
                  ]
                },
                {
                  "type": "AND",
                  "values": [
                    {
                      "key": "age",
                      "operator": "gt",
                      "value": "18"
                    }
                  ]
                }
              ]
            }
            """.trimIndent()

        val expected =
            PropertyGroup(
                type = LogicalOperator.OR,
                values =
                    PropertyValue.PropertyGroups(
                        listOf(
                            PropertyGroup(
                                type = LogicalOperator.AND,
                                values =
                                    PropertyValue.FlagProperties(
                                        listOf(
                                            FlagProperty(
                                                key = "email",
                                                propertyValue = "example.com",
                                                propertyOperator = PropertyOperator.ICONTAINS,
                                                type = null,
                                                negation = null,
                                                dependencyChain = null,
                                            ),
                                        ),
                                    ),
                            ),
                            PropertyGroup(
                                type = LogicalOperator.AND,
                                values =
                                    PropertyValue.FlagProperties(
                                        listOf(
                                            FlagProperty(
                                                key = "age",
                                                propertyValue = "18",
                                                propertyOperator = PropertyOperator.GT,
                                                type = null,
                                                negation = null,
                                                dependencyChain = null,
                                            ),
                                        ),
                                    ),
                            ),
                        ),
                    ),
            )

        assertEquals(expected, gson.fromJson(json, PropertyGroup::class.java))
    }

    @Test
    public fun testPropertyGroupParseWithCohortProperty() {
        val json =
            """
            {
              "type": "AND",
              "values": [
                {
                  "key": "id",
                  "value": 123,
                  "negation": true
                }
              ]
            }
            """.trimIndent()

        val expected =
            PropertyGroup(
                type = LogicalOperator.AND,
                values =
                    PropertyValue.FlagProperties(
                        listOf(
                            FlagProperty(
                                key = "id",
                                propertyValue = 123,
                                propertyOperator = null,
                                type = null,
                                negation = true,
                                dependencyChain = null,
                            ),
                        ),
                    ),
            )

        assertEquals(expected, gson.fromJson(json, PropertyGroup::class.java))
    }

    @Test
    public fun testPropertyGroupParseWithFlagDependency() {
        val json =
            """
            {
              "type": "AND",
              "values": [
                {
                  "key": "feature-flag-key",
                  "operator": "flag_evaluates_to",
                  "value": true,
                  "dependency_chain": ["dep-flag-1", "dep-flag-2"]
                }
              ]
            }
            """.trimIndent()

        val expected =
            PropertyGroup(
                type = LogicalOperator.AND,
                values =
                    PropertyValue.FlagProperties(
                        listOf(
                            FlagProperty(
                                key = "feature-flag-key",
                                propertyValue = true,
                                propertyOperator = PropertyOperator.FLAG_EVALUATES_TO,
                                type = null,
                                negation = null,
                                dependencyChain = listOf("dep-flag-1", "dep-flag-2"),
                            ),
                        ),
                    ),
            )

        assertEquals(expected, gson.fromJson(json, PropertyGroup::class.java))
    }

    @Test
    public fun testPropertyGroupParseEmptyValues() {
        val json =
            """
            {
              "type": "AND",
              "values": []
            }
            """.trimIndent()

        // Empty arrays are deserialized as null
        val expected =
            PropertyGroup(
                type = LogicalOperator.AND,
                values = null,
            )

        assertEquals(expected, gson.fromJson(json, PropertyGroup::class.java))
    }

    @Test
    public fun testPropertyGroupParseNullValues() {
        val json =
            """
            {
              "type": "OR"
            }
            """.trimIndent()

        val expected =
            PropertyGroup(
                type = LogicalOperator.OR,
                values = null,
            )

        assertEquals(expected, gson.fromJson(json, PropertyGroup::class.java))
    }

    @Test
    public fun testPropertyGroupParseNullType() {
        val json =
            """
            {
              "values": [
                {
                  "key": "email",
                  "value": "test@example.com"
                }
              ]
            }
            """.trimIndent()

        val expected =
            PropertyGroup(
                type = null,
                values =
                    PropertyValue.FlagProperties(
                        listOf(
                            FlagProperty(
                                key = "email",
                                propertyValue = "test@example.com",
                                propertyOperator = null,
                                type = null,
                                negation = null,
                                dependencyChain = null,
                            ),
                        ),
                    ),
            )

        assertEquals(expected, gson.fromJson(json, PropertyGroup::class.java))
    }

    @Test
    public fun testPropertyGroupDeserializesTypeField() {
        val json =
            """
            {
              "type": "AND",
              "values": [
                {
                  "key": "id",
                  "type": "cohort",
                  "value": 3,
                  "negation": true
                },
                {
                  "key": "feature-flag",
                  "type": "flag",
                  "operator": "flag_evaluates_to",
                  "value": true
                }
              ]
            }
            """.trimIndent()

        val expected =
            PropertyGroup(
                type = LogicalOperator.AND,
                values =
                    PropertyValue.FlagProperties(
                        listOf(
                            FlagProperty(
                                key = "id",
                                propertyValue = 3,
                                propertyOperator = null,
                                type = PropertyType.COHORT,
                                negation = true,
                                dependencyChain = null,
                            ),
                            FlagProperty(
                                key = "feature-flag",
                                propertyValue = true,
                                propertyOperator = PropertyOperator.FLAG_EVALUATES_TO,
                                type = PropertyType.FLAG,
                                negation = null,
                                dependencyChain = null,
                            ),
                        ),
                    ),
            )

        assertEquals(expected, gson.fromJson(json, PropertyGroup::class.java))
    }

    @Test
    public fun testPropertyGroupsIsEmpty() {
        val emptyGroups = PropertyValue.PropertyGroups(emptyList())
        assertTrue(emptyGroups.isEmpty())

        val nonEmptyGroups =
            PropertyValue.PropertyGroups(
                listOf(
                    PropertyGroup(
                        type = LogicalOperator.AND,
                        values = null,
                    ),
                ),
            )
        assertTrue(!nonEmptyGroups.isEmpty())
    }

    @Test
    public fun testFlagPropertiesIsEmpty() {
        val emptyProperties = PropertyValue.FlagProperties(emptyList())
        assertTrue(emptyProperties.isEmpty())

        val nonEmptyProperties =
            PropertyValue.FlagProperties(
                listOf(
                    FlagProperty(
                        key = "test",
                        propertyValue = "value",
                        propertyOperator = PropertyOperator.EXACT,
                        type = PropertyType.PERSON,
                        negation = false,
                        dependencyChain = null,
                    ),
                ),
            )
        assertTrue(!nonEmptyProperties.isEmpty())
    }

    @Test
    public fun testPropertyOperatorFromString() {
        assertEquals(PropertyOperator.EXACT, PropertyOperator.fromStringOrNull("exact"))
        assertEquals(PropertyOperator.IS_NOT, PropertyOperator.fromStringOrNull("is_not"))
        assertEquals(PropertyOperator.ICONTAINS, PropertyOperator.fromStringOrNull("icontains"))
        assertEquals(PropertyOperator.REGEX, PropertyOperator.fromStringOrNull("regex"))
        assertEquals(PropertyOperator.GT, PropertyOperator.fromStringOrNull("gt"))
        assertEquals(PropertyOperator.IS_DATE_AFTER, PropertyOperator.fromStringOrNull("is_date_after"))
        assertEquals(PropertyOperator.UNKNOWN, PropertyOperator.fromStringOrNull("invalid_operator"))
        assertNull(PropertyOperator.fromStringOrNull(null))
    }

    @Test
    public fun testPropertyTypeFromString() {
        assertEquals(PropertyType.PERSON, PropertyType.fromStringOrNull("person"))
        assertEquals(PropertyType.COHORT, PropertyType.fromStringOrNull("cohort"))
        assertEquals(PropertyType.FLAG, PropertyType.fromStringOrNull("flag"))
        assertEquals(PropertyType.PERSON, PropertyType.fromStringOrNull("invalid_type"))
        assertNull(PropertyType.fromStringOrNull(null))
    }

    @Test
    public fun testDeserializeCohortPropertiesMap() {
        // Test that we can deserialize a map of cohort IDs to PropertyGroups
        val json =
            """
            {
              "2": {
                "type": "AND",
                "values": [
                  {
                    "type": "AND",
                    "values": [
                      {
                        "key": "email",
                        "operator": "not_regex",
                        "type": "person",
                        "value": "@test.com$"
                      }
                    ]
                  }
                ]
              },
              "3": {
                "type": "OR",
                "values": [
                  {
                    "key": "name",
                    "type": "person",
                    "value": "Test"
                  }
                ]
              }
            }
            """.trimIndent()

        val expected =
            mapOf(
                "2" to
                    PropertyGroup(
                        type = LogicalOperator.AND,
                        values =
                            PropertyValue.PropertyGroups(
                                listOf(
                                    PropertyGroup(
                                        type = LogicalOperator.AND,
                                        values =
                                            PropertyValue.FlagProperties(
                                                listOf(
                                                    FlagProperty(
                                                        key = "email",
                                                        propertyValue = "@test.com$",
                                                        propertyOperator = PropertyOperator.NOT_REGEX,
                                                        type = PropertyType.PERSON,
                                                        negation = null,
                                                        dependencyChain = null,
                                                    ),
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                    ),
                "3" to
                    PropertyGroup(
                        type = LogicalOperator.OR,
                        values =
                            PropertyValue.FlagProperties(
                                listOf(
                                    FlagProperty(
                                        key = "name",
                                        propertyValue = "Test",
                                        propertyOperator = null,
                                        type = PropertyType.PERSON,
                                        negation = null,
                                        dependencyChain = null,
                                    ),
                                ),
                            ),
                    ),
            )

        val type = object : TypeToken<Map<String, PropertyGroup>>() {}.type
        assertEquals(expected, gson.fromJson(json, type))
    }

    @Test
    public fun testActualCohortPropertiesStructure() {
        // Test the exact structure used in FlagEvaluatorTest.createCohortProperties()
        val json =
            """
            {
              "2": {
                "type": "AND",
                "values": [
                  {
                    "type": "AND",
                    "values": [
                      {
                        "key": "email",
                        "operator": "not_regex",
                        "type": "person",
                        "value": "@hedgebox.net$"
                      }
                    ]
                  },
                  {
                    "type": "AND",
                    "values": [
                      {
                        "key": "id",
                        "type": "cohort",
                        "negation": true,
                        "value": 3
                      },
                      {
                        "key": "email",
                        "operator": "is_set",
                        "type": "person",
                        "negation": false,
                        "value": "is_set"
                      }
                    ]
                  }
                ]
              }
            }
            """.trimIndent()

        val expected =
            mapOf(
                "2" to
                    PropertyGroup(
                        type = LogicalOperator.AND,
                        values =
                            PropertyValue.PropertyGroups(
                                listOf(
                                    PropertyGroup(
                                        type = LogicalOperator.AND,
                                        values =
                                            PropertyValue.FlagProperties(
                                                listOf(
                                                    FlagProperty(
                                                        key = "email",
                                                        propertyValue = "@hedgebox.net$",
                                                        propertyOperator = PropertyOperator.NOT_REGEX,
                                                        type = PropertyType.PERSON,
                                                        negation = null,
                                                        dependencyChain = null,
                                                    ),
                                                ),
                                            ),
                                    ),
                                    PropertyGroup(
                                        type = LogicalOperator.AND,
                                        values =
                                            PropertyValue.FlagProperties(
                                                listOf(
                                                    FlagProperty(
                                                        key = "id",
                                                        propertyValue = 3,
                                                        propertyOperator = null,
                                                        type = PropertyType.COHORT,
                                                        negation = true,
                                                        dependencyChain = null,
                                                    ),
                                                    FlagProperty(
                                                        key = "email",
                                                        propertyValue = "is_set",
                                                        propertyOperator = PropertyOperator.IS_SET,
                                                        type = PropertyType.PERSON,
                                                        negation = false,
                                                        dependencyChain = null,
                                                    ),
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                    ),
            )

        val type = object : TypeToken<Map<String, PropertyGroup>>() {}.type
        assertEquals(expected, gson.fromJson(json, type))
    }

    @Test
    public fun testComplexNestedCohortStructure() {
        val json =
            """
            {
              "type": "AND",
              "values": [
                {
                  "type": "AND",
                  "values": [
                    {
                      "key": "email",
                      "operator": "not_regex",
                      "value": "@hedgebox.net$"
                    }
                  ]
                },
                {
                  "type": "AND",
                  "values": [
                    {
                      "key": "id",
                      "negation": true,
                      "value": 3
                    },
                    {
                      "key": "email",
                      "operator": "is_set",
                      "negation": false,
                      "value": "is_set"
                    }
                  ]
                }
              ]
            }
            """.trimIndent()

        val expected =
            PropertyGroup(
                type = LogicalOperator.AND,
                values =
                    PropertyValue.PropertyGroups(
                        listOf(
                            PropertyGroup(
                                type = LogicalOperator.AND,
                                values =
                                    PropertyValue.FlagProperties(
                                        listOf(
                                            FlagProperty(
                                                key = "email",
                                                propertyValue = "@hedgebox.net$",
                                                propertyOperator = PropertyOperator.NOT_REGEX,
                                                type = null,
                                                negation = null,
                                                dependencyChain = null,
                                            ),
                                        ),
                                    ),
                            ),
                            PropertyGroup(
                                type = LogicalOperator.AND,
                                values =
                                    PropertyValue.FlagProperties(
                                        listOf(
                                            FlagProperty(
                                                key = "id",
                                                propertyValue = 3,
                                                propertyOperator = null,
                                                type = null,
                                                negation = true,
                                                dependencyChain = null,
                                            ),
                                            FlagProperty(
                                                key = "email",
                                                propertyValue = "is_set",
                                                propertyOperator = PropertyOperator.IS_SET,
                                                type = null,
                                                negation = false,
                                                dependencyChain = null,
                                            ),
                                        ),
                                    ),
                            ),
                        ),
                    ),
            )

        assertEquals(expected, gson.fromJson(json, PropertyGroup::class.java))
    }
}
