package com.posthog.server.internal

import com.posthog.PostHogConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZonedDateTime

internal class FlagEvaluatorTest {
    private lateinit var config: PostHogConfig
    private lateinit var evaluator: FlagEvaluator

    @Before
    internal fun setUp() {
        config = PostHogConfig(apiKey = "test-key")
        evaluator = FlagEvaluator(config)
    }

    @Test
    internal fun testHashConsistency() {
        // Test that hash function returns consistent values for same inputs
        val hash1 = evaluator.getMatchingVariant(createSimpleFlag(), "user-123")
        val hash2 = evaluator.getMatchingVariant(createSimpleFlag(), "user-123")
        assertEquals(hash1, hash2)
    }

    @Test
    internal fun testMatchPropertyExact() {
        val property =
            FlagProperty(
                key = "email",
                value = "test@example.com",
                operator = "exact",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("email" to "test@example.com")
        assertTrue(evaluator.matchProperty(property, properties))
    }

    @Test
    internal fun testMatchPropertyExactCaseInsensitive() {
        val property =
            FlagProperty(
                key = "email",
                value = "TEST@EXAMPLE.COM",
                operator = "exact",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("email" to "test@example.com")
        assertTrue(evaluator.matchProperty(property, properties))
    }

    @Test
    internal fun testMatchPropertyExactUnicodeNormalization() {
        // Test German ß (eszett) - should match "ss" after casefold
        val propertyStrasse =
            FlagProperty(
                key = "location",
                value = "Straße",
                operator = "exact",
                type = "person",
                negation = false,
                dependencyChain = null,
            )

        // Should match lowercase ß
        assertTrue(evaluator.matchProperty(propertyStrasse, mapOf("location" to "straße")))

        // Should match "ss" (casefold normalization)
        assertTrue(evaluator.matchProperty(propertyStrasse, mapOf("location" to "strasse")))

        // Test long s (ſ) - should match regular s after casefold
        val propertyLongS =
            FlagProperty(
                key = "star",
                value = "ſun",
                operator = "exact",
                type = "person",
                negation = false,
                dependencyChain = null,
            )

        // Should match regular s (casefold normalization)
        assertTrue(evaluator.matchProperty(propertyLongS, mapOf("star" to "sun")))

        // Should match exact long s
        assertTrue(evaluator.matchProperty(propertyLongS, mapOf("star" to "ſun")))
    }

    @Test
    internal fun testMatchPropertyExactUnicodeNormalizationWithList() {
        // Test with list values
        val property =
            FlagProperty(
                key = "location",
                value = listOf("Straße", "München"),
                operator = "exact",
                type = "person",
                negation = false,
                dependencyChain = null,
            )

        // Should match with casefold normalization
        assertTrue(evaluator.matchProperty(property, mapOf("location" to "strasse")))
        assertTrue(evaluator.matchProperty(property, mapOf("location" to "munchen")))
    }

    @Test
    internal fun testMatchPropertyExactList() {
        val property =
            FlagProperty(
                key = "browser",
                value = listOf("chrome", "firefox"),
                operator = "exact",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("browser" to "chrome")
        assertTrue(evaluator.matchProperty(property, properties))
    }

    @Test
    internal fun testMatchPropertyIsNot() {
        val property =
            FlagProperty(
                key = "email",
                value = "other@example.com",
                operator = "is_not",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("email" to "test@example.com")
        assertTrue(evaluator.matchProperty(property, properties))
    }

    @Test
    internal fun testMatchPropertyIsSet() {
        val property =
            FlagProperty(
                key = "email",
                value = null,
                operator = "is_set",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("email" to "test@example.com")
        assertTrue(evaluator.matchProperty(property, properties))

        val propertiesWithout = mapOf("name" to "Test")
        try {
            evaluator.matchProperty(property, propertiesWithout)
            assertTrue("Should have thrown InconclusiveMatchException", false)
        } catch (e: InconclusiveMatchException) {
            // Expected
        }
    }

    @Test
    internal fun testMatchPropertyIcontains() {
        val property =
            FlagProperty(
                key = "email",
                value = "example",
                operator = "icontains",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("email" to "test@EXAMPLE.com")
        assertTrue(evaluator.matchProperty(property, properties))
    }

    @Test
    internal fun testMatchPropertyIcontainsTurkishI() {
        // Test Turkish i normalization
        // In Turkish locale, uppercase I → ı (dotless i) and lowercase i → İ (dotted I)
        // The uppercase().lowercase() normalization should handle this
        val property =
            FlagProperty(
                key = "city",
                value = "Istanbul",
                operator = "icontains",
                type = "person",
                negation = false,
                dependencyChain = null,
            )

        // Should match with different casing
        assertTrue(evaluator.matchProperty(property, mapOf("city" to "istanbul")))
        assertTrue(evaluator.matchProperty(property, mapOf("city" to "ISTANBUL")))
        assertTrue(evaluator.matchProperty(property, mapOf("city" to "İstanbul")))
    }

    @Test
    internal fun testMatchPropertyNotIcontains() {
        val property =
            FlagProperty(
                key = "email",
                value = "gmail",
                operator = "not_icontains",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("email" to "test@example.com")
        assertTrue(evaluator.matchProperty(property, properties))
    }

    @Test
    internal fun testMatchPropertyRegex() {
        val property =
            FlagProperty(
                key = "email",
                value = ".*@example\\.com",
                operator = "regex",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("email" to "test@example.com")
        assertTrue(evaluator.matchProperty(property, properties))
    }

    @Test
    internal fun testMatchPropertyNotRegex() {
        val property =
            FlagProperty(
                key = "email",
                value = ".*@gmail\\.com",
                operator = "not_regex",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("email" to "test@example.com")
        assertTrue(evaluator.matchProperty(property, properties))
    }

    @Test
    internal fun testMatchPropertyGreaterThan() {
        val property =
            FlagProperty(
                key = "age",
                value = "18",
                operator = "gt",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("age" to 25)
        assertTrue(evaluator.matchProperty(property, properties))

        val propertiesYounger = mapOf("age" to 15)
        assertFalse(evaluator.matchProperty(property, propertiesYounger))
    }

    @Test
    internal fun testMatchPropertyGreaterThanOrEqual() {
        val property =
            FlagProperty(
                key = "age",
                value = "18",
                operator = "gte",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("age" to 18)
        assertTrue(evaluator.matchProperty(property, properties))
    }

    @Test
    internal fun testMatchPropertyLessThan() {
        val property =
            FlagProperty(
                key = "age",
                value = "65",
                operator = "lt",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("age" to 25)
        assertTrue(evaluator.matchProperty(property, properties))
    }

    @Test
    internal fun testMatchPropertyLessThanOrEqual() {
        val property =
            FlagProperty(
                key = "age",
                value = "65",
                operator = "lte",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("age" to 65)
        assertTrue(evaluator.matchProperty(property, properties))
    }

    @Test
    internal fun testMatchPropertyDateBefore() {
        val property =
            FlagProperty(
                key = "signup_date",
                value = "2024-01-01T00:00:00Z",
                operator = "is_date_before",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("signup_date" to "2023-06-01T00:00:00Z")
        assertTrue(evaluator.matchProperty(property, properties))
    }

    @Test
    internal fun testMatchPropertyDateBeforeVariousFormats() {
        // ISO date only (YYYY-MM-DD)
        val propertyIsoDate =
            FlagProperty(
                key = "signup_date",
                value = "2022-05-01",
                operator = "is_date_before",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        assertTrue(evaluator.matchProperty(propertyIsoDate, mapOf("signup_date" to "2022-03-01")))
        assertTrue(evaluator.matchProperty(propertyIsoDate, mapOf("signup_date" to "2022-04-30")))
        assertFalse(evaluator.matchProperty(propertyIsoDate, mapOf("signup_date" to "2022-05-30")))

        // ISO datetime with timezone offset (with space)
        val propertyWithSpace =
            FlagProperty(
                key = "key",
                value = "2022-04-05 12:34:12 +01:00",
                operator = "is_date_before",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        assertTrue(evaluator.matchProperty(propertyWithSpace, mapOf("key" to "2022-04-05 12:34:11 +01:00")))
        assertFalse(evaluator.matchProperty(propertyWithSpace, mapOf("key" to "2022-04-05 12:34:13 +01:00")))

        // ISO datetime with timezone offset (without space)
        val propertyNoSpace =
            FlagProperty(
                key = "key",
                value = "2022-04-05 12:34:12+01:00",
                operator = "is_date_before",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        assertTrue(evaluator.matchProperty(propertyNoSpace, mapOf("key" to "2022-04-05 12:34:11+01:00")))

        // ISO datetime without timezone
        val propertyNoTz =
            FlagProperty(
                key = "key",
                value = "2022-05-01 00:00:00",
                operator = "is_date_before",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        assertTrue(evaluator.matchProperty(propertyNoTz, mapOf("key" to "2022-04-30 22:00:00")))
    }

    @Test
    internal fun testMatchPropertyDateAfter() {
        val property =
            FlagProperty(
                key = "signup_date",
                value = "2024-01-01T00:00:00Z",
                operator = "is_date_after",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("signup_date" to "2024-06-01T00:00:00Z")
        assertTrue(evaluator.matchProperty(property, properties))
    }

    @Test
    internal fun testMatchPropertyRelativeDate() {
        val property =
            FlagProperty(
                key = "last_seen",
                value = "-7d",
                operator = "is_date_after",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        // Date from yesterday should be after 7 days ago
        val yesterday = ZonedDateTime.now().minusDays(1)
        val properties = mapOf("last_seen" to yesterday)
        assertTrue(evaluator.matchProperty(property, properties))
    }

    @Test
    internal fun testGetMatchingVariant() {
        val flag = createMultiVariateFlag()
        val variant1 = evaluator.getMatchingVariant(flag, "user-with-control")
        val variant2 = evaluator.getMatchingVariant(flag, "user-with-test")

        // Verify that we get consistent variants
        assertNotNull(variant1)
        assertNotNull(variant2)

        // Same user should always get same variant
        assertEquals(variant1, evaluator.getMatchingVariant(flag, "user-with-control"))
        assertEquals(variant2, evaluator.getMatchingVariant(flag, "user-with-test"))
    }

    @Test
    internal fun testMatchFeatureFlagPropertiesSimpleMatch() {
        val flag =
            FlagDefinition(
                id = 1,
                name = "Test Flag",
                key = "test-flag",
                active = true,
                filters =
                    FlagFilters(
                        groups =
                            listOf(
                                FlagConditionGroup(
                                    properties =
                                        listOf(
                                            FlagProperty(
                                                key = "email",
                                                value = "test@example.com",
                                                operator = "exact",
                                                type = "person",
                                                negation = false,
                                                dependencyChain = null,
                                            ),
                                        ),
                                    rolloutPercentage = 100,
                                    variant = null,
                                ),
                            ),
                        multivariate = null,
                        payloads = null,
                    ),
                version = 1,
            )

        val properties = mapOf("email" to "test@example.com")
        val result = evaluator.matchFeatureFlagProperties(flag, "user-123", properties)
        assertEquals(true, result)
    }

    @Test
    internal fun testMatchFeatureFlagPropertiesNoMatch() {
        val flag =
            FlagDefinition(
                id = 1,
                name = "Test Flag",
                key = "test-flag",
                active = true,
                filters =
                    FlagFilters(
                        groups =
                            listOf(
                                FlagConditionGroup(
                                    properties =
                                        listOf(
                                            FlagProperty(
                                                key = "email",
                                                value = "test@example.com",
                                                operator = "exact",
                                                type = "person",
                                                negation = false,
                                                dependencyChain = null,
                                            ),
                                        ),
                                    rolloutPercentage = 100,
                                    variant = null,
                                ),
                            ),
                        multivariate = null,
                        payloads = null,
                    ),
                version = 1,
            )

        val properties = mapOf("email" to "other@example.com")
        val result = evaluator.matchFeatureFlagProperties(flag, "user-123", properties)
        assertEquals(false, result)
    }

    @Test
    internal fun testMatchFeatureFlagPropertiesWithRollout() {
        val flag =
            FlagDefinition(
                id = 1,
                name = "Test Flag",
                key = "test-flag",
                active = true,
                filters =
                    FlagFilters(
                        groups =
                            listOf(
                                FlagConditionGroup(
                                    properties = emptyList(),
                                    rolloutPercentage = 50,
                                    variant = null,
                                ),
                            ),
                        multivariate = null,
                        payloads = null,
                    ),
                version = 1,
            )

        // Test multiple users to verify some match and some don't
        var matchCount = 0
        for (i in 1..100) {
            val result = evaluator.matchFeatureFlagProperties(flag, "user-$i", emptyMap())
            if (result == true) matchCount++
        }

        // With 50% rollout, we should get roughly 50 matches out of 100
        // Allow some variance (40-60)
        assertTrue("Expected ~50 matches, got $matchCount", matchCount in 40..60)
    }

    @Test
    internal fun testMatchFeatureFlagPropertiesWithVariant() {
        val flag = createMultiVariateFlag()
        val result = evaluator.matchFeatureFlagProperties(flag, "user-123", emptyMap())

        // Should return a variant string (control or test)
        assertTrue(result is String)
        assertTrue(result == "control" || result == "test")
    }

    @Test
    internal fun testMissingPropertyThrowsException() {
        val property =
            FlagProperty(
                key = "missing_key",
                value = "test",
                operator = "exact",
                type = "person",
                negation = false,
                dependencyChain = null,
            )
        val properties = mapOf("other_key" to "value")

        try {
            evaluator.matchProperty(property, properties)
            assertTrue("Should have thrown InconclusiveMatchException", false)
        } catch (e: InconclusiveMatchException) {
            assertTrue(e.message?.contains("without a given property value") ?: false)
        }
    }

    // Helper functions

    internal fun createSimpleFlag(): FlagDefinition {
        return FlagDefinition(
            id = 1,
            name = "Simple Flag",
            key = "simple-flag",
            active = true,
            filters =
                FlagFilters(
                    groups =
                        listOf(
                            FlagConditionGroup(
                                properties = emptyList(),
                                rolloutPercentage = 100,
                                variant = null,
                            ),
                        ),
                    multivariate =
                        MultiVariateConfig(
                            variants =
                                listOf(
                                    VariantDefinition(key = "control", rolloutPercentage = 50.0),
                                    VariantDefinition(key = "test", rolloutPercentage = 50.0),
                                ),
                        ),
                    payloads = null,
                ),
            version = 1,
        )
    }

    @Test
    internal fun testMixedConditionsFlag() {
        val flag = createMixedConditionsFlag()
        val withoutSpaces = mapOf("email" to "example@example.com")
        val resultWithoutSpaces = evaluator.matchFeatureFlagProperties(flag, "user-123", withoutSpaces)
        assertEquals(true, resultWithoutSpaces)
    }

    @Test
    internal fun testAllConditionsFlagExactMismatch() {
        val flag = createMixedConditionsFlag()

        // Negative case: email does not match exact condition
        val properties = mapOf("email" to "other@example.com")
        val result = evaluator.matchFeatureFlagProperties(flag, "user-123", properties)
        assertEquals(false, result)
    }

    @Test
    internal fun testAllConditionsFlagIsNotViolation() {
        val flag = createMixedConditionsFlag()

        // Negative case: email matches is_not exclusion list
        val properties = mapOf("email" to "not_example@example.com")
        val result = evaluator.matchFeatureFlagProperties(flag, "user-123", properties)
        assertEquals(false, result)
    }

    @Test
    internal fun testAllConditionsFlagIcontainsMismatch() {
        val flag = createMixedConditionsFlag()

        // Negative case: email does not contain "example"
        val properties = mapOf("email" to "test@test.com")
        val result = evaluator.matchFeatureFlagProperties(flag, "user-123", properties)
        assertEquals(false, result)
    }

    @Test
    internal fun testAllConditionsFlagNotIcontainsViolation() {
        val flag = createMixedConditionsFlag()

        // Negative case: email contains ".net"
        val properties = mapOf("email" to "example@example.net")
        val result = evaluator.matchFeatureFlagProperties(flag, "user-123", properties)
        assertEquals(false, result)
    }

    @Test
    internal fun testAllConditionsFlagRegexMismatch() {
        val flag = createMixedConditionsFlag()

        // Negative case: email does not match regex pattern (invalid format)
        val properties = mapOf("email" to "invalid-email-format")
        val result = evaluator.matchFeatureFlagProperties(flag, "user-123", properties)
        assertEquals(false, result)
    }

    @Test
    internal fun testAllConditionsFlagNotRegexViolation() {
        val flag = createMixedConditionsFlag()

        // Negative case: email matches not_regex exclusion pattern
        val properties = mapOf("email" to "example@example.com@yahoo.com")
        val result = evaluator.matchFeatureFlagProperties(flag, "user-123", properties)
        assertEquals(false, result)
    }

    @Test
    internal fun testAllConditionsFlagIsSetViolation() {
        val flag = createMixedConditionsFlag()

        // Negative case: email is not set
        val properties = mapOf("name" to "Test User")
        try {
            evaluator.matchFeatureFlagProperties(flag, "user-123", properties)
            assertTrue("Should have thrown InconclusiveMatchException", false)
        } catch (e: InconclusiveMatchException) {
            // Expected
        }
    }

    @Test
    internal fun testCohortMemberFlag() {
        val flag =
            FlagDefinition(
                id = 26,
                name = "Cohort Member",
                key = "cohort-member",
                active = true,
                filters =
                    FlagFilters(
                        groups =
                            listOf(
                                FlagConditionGroup(
                                    properties =
                                        listOf(
                                            FlagProperty(
                                                key = "id",
                                                value = 2,
                                                operator = "in",
                                                type = "cohort",
                                                negation = false,
                                                dependencyChain = null,
                                            ),
                                        ),
                                    rolloutPercentage = 100,
                                    variant = null,
                                ),
                            ),
                        multivariate = null,
                        payloads = null,
                    ),
                version = 2,
            )

        val cohortProperties =
            mapOf(
                "2" to
                    CohortDefinition(
                        type = "AND",
                        values =
                            listOf(
                                mapOf(
                                    "type" to "AND",
                                    "values" to
                                        listOf(
                                            mapOf(
                                                "key" to "email",
                                                "operator" to "not_regex",
                                                "type" to "person",
                                                "value" to "@hedgebox.net$",
                                            ),
                                        ),
                                ),
                                mapOf(
                                    "type" to "AND",
                                    "values" to
                                        listOf(
                                            mapOf(
                                                "key" to "id",
                                                "type" to "cohort",
                                                "negation" to true,
                                                "value" to 3,
                                            ),
                                            mapOf(
                                                "key" to "email",
                                                "operator" to "is_set",
                                                "type" to "person",
                                                "negation" to false,
                                                "value" to "is_set",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                "3" to
                    CohortDefinition(
                        type = "OR",
                        values =
                            listOf(
                                mapOf(
                                    "type" to "AND",
                                    "values" to
                                        listOf(
                                            mapOf(
                                                "key" to "email",
                                                "operator" to "regex",
                                                "type" to "person",
                                                "negation" to false,
                                                "value" to "@gmail.com",
                                            ),
                                        ),
                                ),
                            ),
                    ),
            )

        // Positive case: user is in cohort 2 (not hedgebox.net, not gmail, email is set)
        val matchingProperties = mapOf("email" to "example@example.com")
        val result = evaluator.matchFeatureFlagProperties(flag, "user-123", matchingProperties, cohortProperties)
        assertEquals(true, result)
    }

    @Test
    internal fun testCohortMemberFlagHedgeboxUser() {
        val flag = createCohortMemberFlag()
        val cohortProperties = createCohortProperties()

        // Negative case: user has hedgebox.net email (fails cohort 2 first condition)
        val properties = mapOf("email" to "mark.s@hedgebox.net")
        val result = evaluator.matchFeatureFlagProperties(flag, "user-123", properties, cohortProperties)
        assertEquals(false, result)
    }

    @Test
    internal fun testCohortMemberFlagGmailUser() {
        val flag = createCohortMemberFlag()
        val cohortProperties = createCohortProperties()

        // Negative case: user has gmail email (in cohort 3, fails cohort 2 negation)
        val properties = mapOf("email" to "user@gmail.com")
        val result = evaluator.matchFeatureFlagProperties(flag, "user-123", properties, cohortProperties)
        assertEquals(false, result)
    }

    @Test
    internal fun testCohortMemberFlagEmailNotSet() {
        val flag = createCohortMemberFlag()
        val cohortProperties = createCohortProperties()

        // Negative case: email is not set (fails cohort 2 second condition)
        val properties = mapOf("name" to "Test User")
        try {
            evaluator.matchFeatureFlagProperties(flag, "user-123", properties, cohortProperties)
            assertTrue("Should have thrown InconclusiveMatchException", false)
        } catch (e: InconclusiveMatchException) {
            // Expected
        }
    }

    @Test
    internal fun testCohortMemberFlagYahooUser() {
        val flag = createCohortMemberFlag()
        val cohortProperties = createCohortProperties()

        // Positive case: yahoo user is not hedgebox, not gmail, and has email set
        val properties = mapOf("email" to "user@yahoo.com")
        val result = evaluator.matchFeatureFlagProperties(flag, "user-123", properties, cohortProperties)
        assertEquals(true, result)
    }

    @Test
    internal fun testCohortMemberFlagOutlookUser() {
        val flag = createCohortMemberFlag()
        val cohortProperties = createCohortProperties()

        // Positive case: outlook user is not hedgebox, not gmail, and has email set
        val properties = mapOf("email" to "user@outlook.com")
        val result = evaluator.matchFeatureFlagProperties(flag, "user-123", properties, cohortProperties)
        assertEquals(true, result)
    }

    internal fun createMixedConditionsFlag(): FlagDefinition {
        return FlagDefinition(
            id = 25,
            name = "Mixed Conditions",
            key = "mixed-conditions",
            active = true,
            filters =
                FlagFilters(
                    groups =
                        listOf(
                            FlagConditionGroup(
                                properties =
                                    listOf(
                                        FlagProperty(
                                            key = "email",
                                            value = listOf("example@example.com"),
                                            operator = "exact",
                                            type = "person",
                                            negation = false,
                                            dependencyChain = null,
                                        ),
                                        FlagProperty(
                                            key = "email",
                                            value = listOf("not_example@example.com", "also_not_example@example.com"),
                                            operator = "is_not",
                                            type = "person",
                                            negation = false,
                                            dependencyChain = null,
                                        ),
                                        FlagProperty(
                                            key = "email",
                                            value = "example",
                                            operator = "icontains",
                                            type = "person",
                                            negation = false,
                                            dependencyChain = null,
                                        ),
                                        FlagProperty(
                                            key = "email",
                                            value = ".net",
                                            operator = "not_icontains",
                                            type = "person",
                                            negation = false,
                                            dependencyChain = null,
                                        ),
                                        FlagProperty(
                                            key = "email",
                                            value = "\\w+@\\w+\\.\\w+",
                                            operator = "regex",
                                            type = "person",
                                            negation = false,
                                            dependencyChain = null,
                                        ),
                                        FlagProperty(
                                            key = "email",
                                            value = "@yahoo.com$",
                                            operator = "not_regex",
                                            type = "person",
                                            negation = false,
                                            dependencyChain = null,
                                        ),
                                        FlagProperty(
                                            key = "email",
                                            value = "is_set",
                                            operator = "is_set",
                                            type = "person",
                                            negation = false,
                                            dependencyChain = null,
                                        ),
                                    ),
                                rolloutPercentage = 100,
                                variant = null,
                            ),
                        ),
                    multivariate = null,
                    payloads = null,
                ),
            version = 1,
        )
    }

    internal fun createCohortMemberFlag(): FlagDefinition {
        return FlagDefinition(
            id = 26,
            name = "Cohort Member",
            key = "cohort-member",
            active = true,
            filters =
                FlagFilters(
                    groups =
                        listOf(
                            FlagConditionGroup(
                                properties =
                                    listOf(
                                        FlagProperty(
                                            key = "id",
                                            value = 2,
                                            operator = "in",
                                            type = "cohort",
                                            negation = false,
                                            dependencyChain = null,
                                        ),
                                    ),
                                rolloutPercentage = 100,
                                variant = null,
                            ),
                        ),
                    multivariate = null,
                    payloads = null,
                ),
            version = 2,
        )
    }

    internal fun createCohortProperties(): Map<String, CohortDefinition> {
        return mapOf(
            "2" to
                CohortDefinition(
                    type = "AND",
                    values =
                        listOf(
                            mapOf(
                                "type" to "AND",
                                "values" to
                                    listOf(
                                        mapOf(
                                            "key" to "email",
                                            "operator" to "not_regex",
                                            "type" to "person",
                                            "value" to "@hedgebox.net$",
                                        ),
                                    ),
                            ),
                            mapOf(
                                "type" to "AND",
                                "values" to
                                    listOf(
                                        mapOf(
                                            "key" to "id",
                                            "type" to "cohort",
                                            "negation" to true,
                                            "value" to 3,
                                        ),
                                        mapOf(
                                            "key" to "email",
                                            "operator" to "is_set",
                                            "type" to "person",
                                            "negation" to false,
                                            "value" to "is_set",
                                        ),
                                    ),
                            ),
                        ),
                ),
            "3" to
                CohortDefinition(
                    type = "OR",
                    values =
                        listOf(
                            mapOf(
                                "type" to "AND",
                                "values" to
                                    listOf(
                                        mapOf(
                                            "key" to "email",
                                            "operator" to "regex",
                                            "type" to "person",
                                            "negation" to false,
                                            "value" to "@gmail.com",
                                        ),
                                    ),
                            ),
                        ),
                ),
        )
    }

    internal fun createMultiVariateFlag(): FlagDefinition {
        return FlagDefinition(
            id = 1,
            name = "Multi Variate Flag",
            key = "multi-variate-flag",
            active = true,
            filters =
                FlagFilters(
                    groups =
                        listOf(
                            FlagConditionGroup(
                                properties = emptyList(),
                                rolloutPercentage = 100,
                                variant = null,
                            ),
                        ),
                    multivariate =
                        MultiVariateConfig(
                            variants =
                                listOf(
                                    VariantDefinition(key = "control", rolloutPercentage = 50.0),
                                    VariantDefinition(key = "test", rolloutPercentage = 50.0),
                                ),
                        ),
                    payloads = null,
                ),
            version = 1,
        )
    }
}
