package com.posthog.server.internal

import com.posthog.PostHogConfig
import com.posthog.internal.FlagDefinition
import com.posthog.server.PostHogUnresolvedFlagReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Condition groups are ordered alternatives (sdk-specs local-feature-flag-evaluator): they are
 * evaluated in the order they are stored, and `early_exit` stops at a group whose filters all
 * match but whose rollout excludes the user.
 *
 * @see https://github.com/PostHog/posthog-android/issues/991
 */
internal class FlagEvaluatorConditionOrderTest {
    private lateinit var config: PostHogConfig
    private lateinit var evaluator: FlagEvaluator

    @Before
    internal fun setUp() {
        config = PostHogConfig(projectToken = "test-key")
        evaluator = FlagEvaluator(config)
    }

    private fun flag(filters: String): FlagDefinition =
        config.serializer.gson.fromJson(
            """{"id": 1, "name": "Flag", "key": "ordered-flag", "active": true, "filters": $filters}""",
            FlagDefinition::class.java,
        )

    private fun evaluate(
        flag: FlagDefinition,
        properties: Map<String, Any?> = emptyMap(),
    ): Any? = evaluator.matchFeatureFlagProperties(flag, "user-123", properties)

    @Test
    internal fun testEarlierGroupWinsOverALaterVariantOverride() {
        // Everyone matches the first group, which has no override. The hashed variant is always
        // "control", so "test" can only come from the second group's override.
        val flag =
            flag(
                """
                {
                  "groups": [
                    {"properties": [], "rollout_percentage": 100},
                    {"properties": [], "rollout_percentage": 100, "variant": "test"}
                  ],
                  "multivariate": {"variants": [
                    {"key": "control", "rollout_percentage": 100},
                    {"key": "test", "rollout_percentage": 0}
                  ]}
                }
                """.trimIndent(),
            )

        assertEquals("control", evaluate(flag))
    }

    @Test
    internal fun testVariantOverrideAppliesWhenItsGroupIsTheFirstMatch() {
        val flag =
            flag(
                """
                {
                  "groups": [
                    {"properties": [{"key": "plan", "value": "pro", "operator": "exact", "type": "person"}], "rollout_percentage": 100},
                    {"properties": [], "rollout_percentage": 100, "variant": "test"}
                  ],
                  "multivariate": {"variants": [
                    {"key": "control", "rollout_percentage": 100},
                    {"key": "test", "rollout_percentage": 0}
                  ]}
                }
                """.trimIndent(),
            )

        assertEquals("test", evaluate(flag, mapOf("plan" to "free")))
        assertEquals("control", evaluate(flag, mapOf("plan" to "pro")))
    }

    private fun excludedThenEveryone(earlyExit: String): FlagDefinition =
        flag(
            """
            {
              $earlyExit
              "groups": [
                {"properties": [], "rollout_percentage": 0},
                {"properties": [], "rollout_percentage": 100}
              ]
            }
            """.trimIndent(),
        )

    @Test
    internal fun testEarlyExitStopsAtARolloutExclusion() {
        assertEquals(false, evaluate(excludedThenEveryone(""""early_exit": true,""")))
    }

    @Test
    internal fun testWithoutEarlyExitARolloutExclusionFallsThrough() {
        assertEquals(true, evaluate(excludedThenEveryone(""""early_exit": false,""")))
        assertEquals(true, evaluate(excludedThenEveryone("")))
    }

    @Test
    internal fun testEarlyExitDefaultsToOffWhenAbsentOrNull() {
        assertFalse(excludedThenEveryone("").filters.earlyExit)
        assertFalse(excludedThenEveryone(""""early_exit": null,""").filters.earlyExit)
        assertTrue(excludedThenEveryone(""""early_exit": true,""").filters.earlyExit)
    }

    @Test
    internal fun testEarlyExitStillFallsThroughAFilterMismatch() {
        val flag =
            flag(
                """
                {
                  "early_exit": true,
                  "groups": [
                    {"properties": [{"key": "plan", "value": "pro", "operator": "exact", "type": "person"}], "rollout_percentage": 0},
                    {"properties": [], "rollout_percentage": 100}
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(true, evaluate(flag, mapOf("plan" to "free")))
        // Filters match and the rollout excludes the user: stop.
        assertEquals(false, evaluate(flag, mapOf("plan" to "pro")))
    }

    @Test
    internal fun testEarlyExitAfterAnInconclusiveGroupStaysInconclusive() {
        val flag =
            flag(
                """
                {
                  "early_exit": true,
                  "groups": [
                    {"properties": [{"key": "plan", "value": "pro", "operator": "exact", "type": "person"}], "rollout_percentage": 100},
                    {"properties": [], "rollout_percentage": 0},
                    {"properties": [], "rollout_percentage": 100}
                  ]
                }
                """.trimIndent(),
            )

        val error =
            kotlin.test.assertFailsWith<InconclusiveMatchException> {
                evaluate(flag)
            }
        assertEquals(PostHogUnresolvedFlagReason.MISSING_CONTEXT, error.reason)
    }
}
