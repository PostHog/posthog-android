package com.posthog.server.internal

import com.posthog.PostHogConfig
import com.posthog.internal.FlagDefinition
import com.posthog.internal.PostHogApi
import com.posthog.server.PostHogUnresolvedFlagReason
import com.posthog.server.TestLogger
import com.posthog.server.createFlagsResponse
import com.posthog.server.createMockHttp
import com.posthog.server.createTestConfig
import com.posthog.server.jsonResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Early-access features: the person's opt-in in `$feature_enrollment/<flag key>` decides the flag
 * before holdouts and release conditions (sdk-specs local-feature-flag-evaluator,
 * local-feature-flag-enrollment.feature).
 *
 * @see https://github.com/PostHog/posthog/issues/138580
 */
internal class FeatureEnrollmentTest {
    private val enrollmentKey = "\$feature_enrollment/beta-feature"
    private val config = PostHogConfig(projectToken = "test-key")
    private val evaluator = FlagEvaluator(config)

    private fun betaFeature(
        filters: String = """"groups": [{"properties": [], "rollout_percentage": 100}]""",
        extra: String = "",
    ): String = """{"id": 1, "key": "beta-feature", "active": true, $extra "filters": {"feature_enrollment": true, $filters}}"""

    private fun parse(json: String): FlagDefinition = config.serializer.gson.fromJson(json, FlagDefinition::class.java)

    private fun evaluate(
        flag: String,
        properties: Map<String, Any?>,
    ): Any? = evaluator.matchFeatureFlagProperties(parse(flag), "user-123", properties)

    @Test
    fun `enrollment values decide before release conditions`() {
        val everyoneOff = """"groups": [{"properties": [], "rollout_percentage": 0}]"""
        val cases =
            listOf<Pair<Any?, Boolean>>(
                true to true,
                "true" to true,
                false to false,
                "false" to false,
                1 to false,
                0 to false,
                null to false,
                "True" to false,
                "" to false,
                emptyList<Any>() to false,
                emptyMap<String, Any>() to false,
            )
        for ((value, expected) in cases) {
            // Release conditions give the opposite answer, so only enrollment can produce `expected`.
            val filters = if (expected) everyoneOff else """"groups": [{"properties": [], "rollout_percentage": 100}]"""
            assertEquals(expected, evaluate(betaFeature(filters), mapOf(enrollmentKey to value)), "enrollment $value")
        }
    }

    @Test
    fun `missing enrollment is inconclusive`() {
        val error = assertFailsWith<InconclusiveMatchException> { evaluate(betaFeature(), emptyMap()) }
        assertEquals(PostHogUnresolvedFlagReason.MISSING_CONTEXT, error.reason)
    }

    @Test
    fun `experience continuity still wins`() {
        val flag = betaFeature(extra = """"ensure_experience_continuity": true,""")
        for (properties in listOf(mapOf(enrollmentKey to true), mapOf(enrollmentKey to false), emptyMap())) {
            val error = assertFailsWith<InconclusiveMatchException> { evaluate(flag, properties) }
            assertEquals(PostHogUnresolvedFlagReason.EXPERIENCE_CONTINUITY, error.reason)
        }
    }

    @Test
    fun `a false or absent marker keeps ordinary targeting`() {
        for (marker in listOf(""""feature_enrollment": false,""", """"feature_enrollment": null,""", "")) {
            val groups = """"groups": [{"properties": [], "rollout_percentage": 100}]"""
            val flag = """{"id": 1, "key": "beta-feature", "active": true, "filters": {$marker $groups}}"""
            assertFalse(parse(flag).filters.featureEnrollment)
            assertEquals(true, evaluate(flag, emptyMap()))
        }
    }

    @Test
    fun `the definition check skips release conditions an enrollment flag never reads`() {
        // A static cohort that is not in the definitions: unsupported, but never read.
        val staticCohort = """"groups": [{"properties": [{"key": "id", "type": "cohort", "value": 123}], "rollout_percentage": 100}]"""
        val flag = parse(betaFeature(staticCohort))
        assertNull(evaluator.definitionUnresolvedReason(flag, mapOf("beta-feature" to flag), emptyMap(), emptyMap()))
        assertEquals(true, evaluate(betaFeature(staticCohort), mapOf(enrollmentKey to true)))
        assertEquals(false, evaluate(betaFeature(staticCohort), mapOf(enrollmentKey to false)))
    }

    @Test
    fun `a dependent flag uses the enrollment`() {
        val beta = parse(betaFeature(""""groups": [{"properties": [], "rollout_percentage": 0}]"""))
        val dependent =
            parse(
                """
                {"id": 2, "key": "dependent", "active": true, "filters": {"groups": [{"properties": [
                  {"key": "beta-feature", "type": "flag", "operator": "flag_evaluates_to", "value": true,
                   "dependency_chain": ["beta-feature"]}
                ], "rollout_percentage": 100}]}}
                """.trimIndent(),
            )
        val flags = mapOf("beta-feature" to beta, "dependent" to dependent)
        for (enrolled in listOf(true, false)) {
            assertEquals(
                enrolled,
                evaluator.matchFeatureFlagProperties(
                    dependent,
                    "user-123",
                    mapOf(enrollmentKey to enrolled),
                    flagsByKey = flags,
                    evaluationCache = mutableMapOf(),
                ),
            )
        }
    }

    private fun groupFlagClient(
        rollout: Int,
        remoteEnabled: Boolean,
    ): Pair<PostHogFeatureFlags, okhttp3.mockwebserver.MockWebServer> {
        val definitions =
            """
            {"flags": [${betaFeature(
                """"aggregation_group_type_index": 0, "payloads": {"true": "{\"plan\":\"beta\"}"},
                "groups": [{"properties": [], "rollout_percentage": $rollout}]""",
            )}],
             "group_type_mapping": {"0": "company"}, "cohorts": {}}
            """.trimIndent()
        val server =
            createMockHttp(
                jsonResponse(definitions),
                jsonResponse(createFlagsResponse("beta-feature", enabled = remoteEnabled)),
            )
        val testConfig = createTestConfig(host = server.url("/").toString())
        val flags =
            PostHogFeatureFlags(
                testConfig,
                PostHogApi(testConfig),
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
            )
        flags.loadFeatureFlagDefinitions()
        return flags to server
    }

    @Test
    fun `a group flag reads the person's enrollment without group context`() {
        val (flags, server) = groupFlagClient(rollout = 0, remoteEnabled = false)
        try {
            assertEquals(
                true,
                flags.getFeatureFlag("beta-feature", distinctId = "user-123", personProperties = mapOf(enrollmentKey to true)),
            )
            assertEquals(1, server.requestCount, "evaluated locally, no /flags request")
        } finally {
            flags.shutDown()
            server.shutdown()
        }
    }

    @Test
    fun `group properties can't turn a person's opt-out into enrollment`() {
        val (flags, server) = groupFlagClient(rollout = 100, remoteEnabled = true)
        try {
            assertEquals(
                false,
                flags.getFeatureFlag(
                    "beta-feature",
                    distinctId = "user-123",
                    groups = mapOf("company" to "acme"),
                    personProperties = mapOf(enrollmentKey to false),
                    groupProperties = mapOf("company" to mapOf(enrollmentKey to true)),
                ),
            )
            assertEquals(1, server.requestCount, "evaluated locally, no /flags request")
        } finally {
            flags.shutDown()
            server.shutdown()
        }
    }

    @Test
    fun `an enrolled flag has its payload and an opted-out one has none`() {
        val (flags, server) = groupFlagClient(rollout = 100, remoteEnabled = true)
        try {
            for ((enrolled, payload) in listOf(true to "{\"plan\":\"beta\"}", false to null)) {
                val properties = mapOf(enrollmentKey to enrolled)
                val result = flags.getFeatureFlagResult("beta-feature", "user-123", null, properties, null)
                assertEquals(enrolled, result?.enabled)
                assertEquals(payload, result?.payload)
            }
            assertEquals(1, server.requestCount, "evaluated locally, no /flags request")
        } finally {
            flags.shutDown()
            server.shutdown()
        }
    }

    @Test
    fun `missing enrollment falls back to flags even when release conditions match`() {
        val (flags, server) = groupFlagClient(rollout = 100, remoteEnabled = false)
        try {
            assertEquals(
                false,
                flags.getFeatureFlag("beta-feature", distinctId = "user-123", groups = mapOf("company" to "acme")),
            )
            assertEquals(2, server.requestCount, "fell back to /flags")
        } finally {
            flags.shutDown()
            server.shutdown()
        }
    }

    private val everyone = """"groups": [{"properties": [], "rollout_percentage": 100}]"""
    private val noOne = """"groups": [{"properties": [], "rollout_percentage": 0}]"""
    private val byCompany = """"aggregation_group_type_index": 0,"""
    private val control = """{"id": 9, "key": "control", "active": true, "filters": {$everyone}}"""

    private fun dependent(filters: String = ""): String =
        """
        {"id": 2, "key": "dependent", "active": true, "filters": {$filters "groups": [{"properties": [
          {"key": "beta-feature", "type": "flag", "operator": "flag_evaluates_to", "value": true,
           "dependency_chain": ["beta-feature"]}
        ], "rollout_percentage": 100}]}}
        """.trimIndent()

    /** Loads the flags and evaluates them all locally: values by key, then why the others have none. */
    private fun evaluateAll(
        vararg definitions: String,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
        cohorts: String = "{}",
        logger: TestLogger = TestLogger(),
    ): Pair<Map<String, Boolean>, Map<String, PostHogUnresolvedFlagReason>> {
        val server =
            createMockHttp(
                jsonResponse(
                    """
                    {"flags": [${definitions.joinToString()}], "group_type_mapping": {"0": "company"}, "cohorts": $cohorts}
                    """.trimIndent(),
                ),
            )
        val testConfig = createTestConfig(logger = logger, host = server.url("/").toString())
        val flags =
            PostHogFeatureFlags(
                testConfig,
                PostHogApi(testConfig),
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
            )
        try {
            flags.loadFeatureFlagDefinitions()
            val result =
                flags.evaluateFlags(
                    distinctId = "user-123",
                    groups = groups,
                    personProperties = personProperties,
                    groupProperties = groupProperties,
                    flagKeys = null,
                    onlyEvaluateLocally = true,
                    disableGeoip = false,
                )
            assertEquals(1, server.requestCount, "only the definitions were requested")
            return result.flags.mapValues { it.value.enabled } to result.unresolvedFlags
        } finally {
            flags.shutDown()
            server.shutdown()
        }
    }

    @Test
    fun `a group flag depending on an enrollment flag reads the person's enrollment`() {
        val company = mapOf("company" to "acme")
        val cases =
            listOf(
                // The group has other properties, or the opposite enrollment.
                Triple(true, mapOf("plan" to "enterprise"), true),
                Triple(false, mapOf(enrollmentKey to true), false),
                Triple(true, mapOf(enrollmentKey to false), true),
            )
        for ((enrolled, group, expected) in cases) {
            val (values, unresolved) =
                evaluateAll(
                    betaFeature(noOne),
                    dependent(byCompany),
                    groups = company,
                    personProperties = mapOf(enrollmentKey to enrolled),
                    groupProperties = mapOf("company" to group),
                )
            assertEquals(mapOf("beta-feature" to expected, "dependent" to expected), values)
            assertEquals(emptyMap(), unresolved)
        }
    }

    @Test
    fun `a group flag depending on an enrollment flag through a cohort reads the person's enrollment`() {
        val viaCohort =
            """
            {"id": 3, "key": "via-cohort", "active": true, "filters": {$byCompany "groups": [{"properties": [
              {"key": "id", "type": "cohort", "value": 7}], "rollout_percentage": 100}]}}
            """.trimIndent()
        val (values, unresolved) =
            evaluateAll(
                betaFeature(noOne),
                viaCohort,
                groups = mapOf("company" to "acme"),
                personProperties = mapOf(enrollmentKey to false),
                groupProperties = mapOf("company" to mapOf(enrollmentKey to true)),
                cohorts =
                    """
                    {"7": {"type": "AND", "values": [{"type": "AND", "values": [
                      {"key": "beta-feature", "type": "flag", "operator": "flag_evaluates_to", "value": true,
                       "dependency_chain": ["beta-feature"]}]}]}}
                    """.trimIndent(),
            )
        assertEquals(mapOf("beta-feature" to false, "via-cohort" to false), values)
        assertEquals(emptyMap(), unresolved)
    }

    @Test
    fun `a group enrollment flag with experience continuity stays unresolved without groups`() {
        val (values, unresolved) =
            evaluateAll(
                betaFeature("$byCompany $everyone", extra = """"ensure_experience_continuity": true,"""),
                personProperties = mapOf(enrollmentKey to true),
            )
        assertEquals(emptyMap(), values)
        assertEquals(mapOf("beta-feature" to PostHogUnresolvedFlagReason.EXPERIENCE_CONTINUITY), unresolved)
    }

    @Test
    fun `missing enrollment leaves only the flag and its dependents unresolved`() {
        val (values, unresolved) = evaluateAll(betaFeature(), dependent(), control)
        assertEquals(mapOf("control" to true), values)
        assertEquals(
            mapOf(
                "beta-feature" to PostHogUnresolvedFlagReason.MISSING_CONTEXT,
                "dependent" to PostHogUnresolvedFlagReason.UNRESOLVED_DEPENDENCY,
            ),
            unresolved,
        )
    }

    @Test
    fun `group properties don't stand in for a missing enrollment`() {
        val (values, unresolved) =
            evaluateAll(
                betaFeature("$byCompany $everyone"),
                groups = mapOf("company" to "acme"),
                groupProperties = mapOf("company" to mapOf(enrollmentKey to true)),
            )
        assertEquals(emptyMap(), values)
        assertEquals(mapOf("beta-feature" to PostHogUnresolvedFlagReason.MISSING_CONTEXT), unresolved)
    }

    @Test
    fun `an enrollment flag with a static cohort only misses context and isn't warned about`() {
        val logger = TestLogger()
        val staticCohort = """"groups": [{"properties": [{"key": "id", "type": "cohort", "value": 123}], "rollout_percentage": 100}]"""
        val (_, unresolved) = evaluateAll(betaFeature(staticCohort), logger = logger)
        assertEquals(mapOf("beta-feature" to PostHogUnresolvedFlagReason.MISSING_CONTEXT), unresolved)
        assertEquals(emptyList<String>(), logger.warnings.toList())
    }

    @Test
    fun `an inactive enrollment flag is off whatever was passed`() {
        val inactive = betaFeature().replace(""""active": true""", """"active": false""")
        for (properties in listOf(mapOf(enrollmentKey to true), null)) {
            val (values, unresolved) = evaluateAll(inactive, personProperties = properties)
            assertEquals(mapOf("beta-feature" to false), values)
            assertEquals(emptyMap(), unresolved)
        }
    }
}
