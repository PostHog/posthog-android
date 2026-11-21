package com.posthog.server.internal

import com.posthog.PostHogConfig
import com.posthog.internal.FlagDefinition
import com.posthog.internal.PostHogApi
import com.posthog.internal.PropertyType
import com.posthog.server.PostHogUnresolvedFlagReason
import com.posthog.server.createFlagsResponse
import com.posthog.server.createMockHttp
import com.posthog.server.createTestConfig
import com.posthog.server.jsonResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A cohort can filter on Person metadata > First seen (`type: "person_metadata"`, key
 * `created_at`). `/flags` reads that value from the persons table and ignores a `created_at`
 * person property, so local evaluation must not match it against one.
 *
 * @see https://github.com/PostHog/posthog/issues/137503
 */
internal class PersonMetadataFilterTest {
    private val config = PostHogConfig(projectToken = "test-key")
    private val evaluator = FlagEvaluator(config)
    private val firstSeen = """{"key": "created_at", "type": "person_metadata", "operator": "is_date_after", "value": "2025-01-01"}"""
    private val createdIn2026 = mapOf<String, Any?>("created_at" to "2026-06-01")

    private fun flag(properties: String): String =
        """{"id": 1, "key": "first-seen-flag", "active": true,
            "filters": {"groups": [{"properties": [$properties], "rollout_percentage": 100}]}}"""

    private fun parse(json: String): FlagDefinition = config.serializer.gson.fromJson(json, FlagDefinition::class.java)

    @Test
    fun `filter types parse to their kind, and unknown ones to unsupported`() {
        assertEquals(PropertyType.PERSON, PropertyType.fromString("person"))
        assertEquals(PropertyType.PERSON, PropertyType.fromString("group"))
        assertEquals(PropertyType.COHORT, PropertyType.fromString("cohort"))
        assertEquals(PropertyType.FLAG, PropertyType.fromString("flag"))
        assertEquals(PropertyType.UNSUPPORTED, PropertyType.fromString("person_metadata"))
    }

    @Test
    fun `a person_metadata filter in a flag condition is unsupported`() {
        val error =
            assertFailsWith<InconclusiveMatchException> {
                evaluator.matchFeatureFlagProperties(parse(flag(firstSeen)), "user-123", createdIn2026)
            }
        assertEquals(PostHogUnresolvedFlagReason.UNSUPPORTED_DEFINITION, error.reason)
    }

    @Test
    fun `a person filter on created_at is still evaluated locally`() {
        val personFilter = firstSeen.replace("person_metadata", "person")
        assertEquals(true, evaluator.matchFeatureFlagProperties(parse(flag(personFilter)), "user-123", createdIn2026))
    }

    @Test
    fun `the definition check reports a person_metadata filter as unsupported`() {
        val definition = parse(flag(firstSeen))
        assertEquals(
            PostHogUnresolvedFlagReason.UNSUPPORTED_DEFINITION,
            evaluator.definitionUnresolvedReason(definition, mapOf("first-seen-flag" to definition), emptyMap(), emptyMap()),
        )
    }

    @Test
    fun `a person_metadata filter in a cohort falls back to flags`() {
        val definitions =
            """
            {"flags": [${flag("""{"key": "id", "type": "cohort", "value": 7}""")}],
             "group_type_mapping": {},
             "cohorts": {"7": {"type": "AND", "values": [$firstSeen]}}}
            """.trimIndent()
        // The person was created in 2020, so /flags answers false.
        val server =
            createMockHttp(
                jsonResponse(definitions),
                jsonResponse(createFlagsResponse("first-seen-flag", enabled = false)),
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
        try {
            flags.loadFeatureFlagDefinitions()
            assertEquals(
                false,
                flags.getFeatureFlag("first-seen-flag", distinctId = "user-123", personProperties = createdIn2026),
            )
            assertEquals(2, server.requestCount, "fell back to /flags")
        } finally {
            flags.shutDown()
            server.shutdown()
        }
    }
}
