package com.posthog.server

import com.google.gson.Gson
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A group key that is an empty string, or a null value from a Java caller, counts as no group key:
 * the flags service skips a condition aggregated on that group type, so the flag is false locally
 * without a /flags fallback. The public group maps take string values, so other non-string kinds
 * cannot be passed.
 */
@Suppress("DEPRECATION")
internal class PostHogUnusableGroupKeyTest {
    private class RecordingDispatcher(
        private val definitions: String,
        private val flagsResponse: String,
    ) : Dispatcher() {
        val flagsRequests: MutableList<String> = CopyOnWriteArrayList()
        val capturedEvents: MutableList<Map<String, Any?>> = CopyOnWriteArrayList()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.contains("local_evaluation") -> jsonResponse(definitions)
                path.contains("/flags") -> {
                    flagsRequests.add(request.body.unGzip())
                    jsonResponse(flagsResponse)
                }
                path.contains("/batch") -> {
                    request.parseBatch().batch.forEach {
                        @Suppress("UNCHECKED_CAST")
                        capturedEvents.add(Gson().fromJson(it, Map::class.java) as Map<String, Any?>)
                    }
                    MockResponse().setResponseCode(200)
                }
                else -> MockResponse().setResponseCode(200)
            }
        }
    }

    private fun withPostHog(
        definitions: String,
        block: (PostHogInterface, RecordingDispatcher) -> Unit,
    ) {
        val dispatcher =
            RecordingDispatcher(
                definitions,
                // Only the flag that is inconclusive with a usable key may come from /flags.
                createMultipleFlagsResponse("org-region" to true, "org-holdout" to true),
            )
        val mockServer = MockWebServer()
        mockServer.dispatcher = dispatcher
        mockServer.start()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_PROJECT_TOKEN)
                    .host(mockServer.url("/").toString())
                    .personalApiKey("phx_test_personal_api_key")
                    .sendFeatureFlagEvent(false)
                    .flushAt(1)
                    .build(),
            )
        try {
            block(postHog, dispatcher)
        } finally {
            postHog.close()
            mockServer.shutdown()
        }
    }

    private fun definitions(
        orgFlagRollout: Int = 100,
        includeOrgRegion: Boolean = true,
    ): String =
        """
        {"flags": [
          {"id": 1, "key": "org-flag", "name": "org-flag", "active": true, "version": 1,
           "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [], "rollout_percentage": $orgFlagRollout}]}},
          {"id": 2, "key": "person-child", "name": "person-child", "active": true, "version": 1,
           "filters": {"groups": [{"properties": [
             {"key": "org-flag", "type": "flag", "operator": "flag_evaluates_to", "value": true, "dependency_chain": ["org-flag"]}
           ], "rollout_percentage": 100}]}},
          {"id": 3, "key": "mixed", "name": "mixed", "active": true, "version": 1,
           "filters": {"groups": [
             {"properties": [
               {"key": "org-flag", "type": "flag", "operator": "flag_evaluates_to", "value": true, "dependency_chain": ["org-flag"]}
             ], "rollout_percentage": 100},
             {"properties": [{"key": "plan", "type": "person", "operator": "exact", "value": ["pro"]}], "rollout_percentage": 100}
           ]}}${if (includeOrgRegion) "," else ""}
          ${if (includeOrgRegion) ORG_REGION_FLAG else ""}
         ],
         "group_type_mapping": {"0": "organization"},
         "cohorts": {}}
        """.trimIndent()

    private fun holdoutDefinitions(): String =
        """
        {"flags": [
          {"id": 5, "key": "org-holdout", "name": "org-holdout", "active": true, "version": 1,
           "filters": {"aggregation_group_type_index": 0, "holdout": {"id": 7, "exclusion_percentage": 50},
                       "groups": [{"properties": [], "rollout_percentage": 100}]}},
          {"id": 6, "key": "org-holdout-zero", "name": "org-holdout-zero", "active": true, "version": 1,
           "filters": {"aggregation_group_type_index": 0, "holdout": {"id": 8, "exclusion_percentage": 0},
                       "groups": [{"properties": [], "rollout_percentage": 100}]}}
         ],
         "group_type_mapping": {"0": "organization"},
         "cohorts": {}}
        """.trimIndent()

    private companion object {
        // Inconclusive with a usable key that has no "region" property, so it is answered by /flags.
        val ORG_REGION_FLAG =
            """
            {"id": 4, "key": "org-region", "name": "org-region", "active": true, "version": 1,
             "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
               {"key": "region", "type": "group", "group_type_index": 0, "operator": "exact", "value": ["us"]}
             ], "rollout_percentage": 100}]}}
            """.trimIndent()
    }

    /** Every unusable group key the public `Map<String, String>` group parameters can carry. */
    private fun unusableGroups(): List<Pair<String, Map<String, String>>> {
        val nullKey = java.util.HashMap<String, String?>()
        nullKey["organization"] = null
        @Suppress("UNCHECKED_CAST")
        return listOf(
            "empty string" to mapOf("organization" to ""),
            "null" to (nullKey as Map<String, String>),
        )
    }

    private fun localOnly(
        groups: Map<String, String>,
        personProperties: Map<String, Any?> = emptyMap(),
    ): PostHogEvaluateFlagsOptions =
        PostHogEvaluateFlagsOptions.builder()
            .groups(groups)
            .personProperties(personProperties)
            .onlyEvaluateLocally(true)
            .build()

    @Test
    fun `a group flag with an unusable group key is false locally without a flags request`() {
        withPostHog(definitions()) { postHog, dispatcher ->
            for ((kind, groups) in unusableGroups()) {
                val local = postHog.evaluateFlags("user-1", localOnly(groups))
                assertEquals(false, local.getFlag("org-flag"), kind)

                val options = PostHogEvaluateFlagsOptions.builder().groups(groups).flagKeys(listOf("org-flag")).build()
                val snapshot = postHog.evaluateFlags("user-1", options)
                assertEquals(false, snapshot.getFlag("org-flag"), kind)
                assertFalse(snapshot.isEnabled("org-flag"), kind)

                assertEquals(false, postHog.getFeatureFlag("user-1", "org-flag", groups = groups), kind)
                assertFalse(postHog.isFeatureEnabled("user-1", "org-flag", groups = groups), kind)
                val result = postHog.getFeatureFlagResult("user-1", "org-flag", groups = groups)
                assertNotNull(result, kind)
                assertFalse(result.enabled, kind)
                assertEquals(0, dispatcher.flagsRequests.size, kind)
            }
        }
    }

    @Test
    fun `an unusable group key skips the group condition and a person condition still decides`() {
        withPostHog(definitions()) { postHog, dispatcher ->
            for ((kind, groups) in unusableGroups()) {
                assertTrue(postHog.evaluateFlags("user-1", localOnly(groups, mapOf("plan" to "pro"))).isEnabled("mixed"), kind)
                assertEquals(false, postHog.evaluateFlags("user-1", localOnly(groups, mapOf("plan" to "free"))).getFlag("mixed"), kind)

                val options =
                    PostHogEvaluateFlagsOptions.builder()
                        .groups(groups)
                        .personProperty("plan", "free")
                        .flagKeys(listOf("mixed"))
                        .build()
                assertEquals(false, postHog.evaluateFlags("user-1", options).getFlag("mixed"), kind)
                assertEquals(0, dispatcher.flagsRequests.size, kind)
            }
        }
    }

    @Test
    fun `a group dependency with an unusable group key is false`() {
        withPostHog(definitions()) { postHog, dispatcher ->
            for ((kind, groups) in unusableGroups()) {
                assertEquals(false, postHog.evaluateFlags("user-1", localOnly(groups)).getFlag("person-child"), kind)
                assertEquals(false, postHog.getFeatureFlag("user-1", "person-child", groups = groups), kind)
                assertEquals(0, dispatcher.flagsRequests.size, kind)
            }
        }
    }

    @Test
    fun `an unusable group key does not crash bulk evaluation or a capture that appends flags`() {
        // Without the inconclusive org-region flag and with the person property "mixed" needs, so
        // every flag resolves locally and the deprecated bulk path does not call /flags.
        withPostHog(definitions(includeOrgRegion = false)) { postHog, dispatcher ->
            for ((kind, groups) in unusableGroups()) {
                val options = PostHogEvaluateFlagsOptions.builder().groups(groups).personProperty("plan", "free").build()
                val snapshot = postHog.evaluateFlags("user-1", options)
                assertEquals(false, snapshot.getFlag("org-flag"), kind)
                assertEquals(false, snapshot.getFlag("person-child"), kind)
                assertEquals(false, snapshot.getFlag("mixed"), kind)

                postHog.capture(
                    "user-1",
                    "event-$kind",
                    userProperties = mapOf("plan" to "free"),
                    groups = groups,
                    appendFeatureFlags = true,
                )
            }
            postHog.flush()
            val deadline = System.currentTimeMillis() + 5_000
            while (dispatcher.capturedEvents.size < unusableGroups().size && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            for ((kind, _) in unusableGroups()) {
                val event = dispatcher.capturedEvents.single { it["event"] == "event-$kind" }

                @Suppress("UNCHECKED_CAST")
                val properties = event["properties"] as Map<String, Any?>
                assertEquals(false, properties["\$feature/org-flag"], kind)
                assertEquals(false, properties["\$feature/person-child"], kind)
                assertEquals(false, properties["\$feature/mixed"], kind)
            }
            assertEquals(0, dispatcher.flagsRequests.size)
        }
    }

    @Test
    fun `a group flag with a holdout and an unusable group key is missing context`() {
        withPostHog(holdoutDefinitions()) { postHog, dispatcher ->
            for ((kind, groups) in unusableGroups()) {
                val local = postHog.evaluateFlags("user-1", localOnly(groups))
                assertFalse(local.keys.contains("org-holdout"), kind)
                assertEquals(PostHogUnresolvedFlagReason.MISSING_CONTEXT, local.unresolvedFlags["org-holdout"], kind)
                assertEquals(0, dispatcher.flagsRequests.size, kind)

                assertEquals(true, postHog.getFeatureFlag("user-1", "org-holdout", groups = groups), kind)
                assertEquals(1, dispatcher.flagsRequests.size, kind)
                dispatcher.flagsRequests.clear()
            }
        }
    }

    @Test
    fun `a zero holdout or a usable group key leaves the holdout ignored`() {
        withPostHog(holdoutDefinitions()) { postHog, dispatcher ->
            for ((kind, groups) in unusableGroups()) {
                assertEquals(false, postHog.evaluateFlags("user-1", localOnly(groups)).getFlag("org-holdout-zero"), kind)
                assertEquals(false, postHog.getFeatureFlag("user-1", "org-holdout-zero", groups = groups), kind)
            }
            val usable = postHog.evaluateFlags("user-1", localOnly(mapOf("organization" to "org-1")))
            assertEquals(true, usable.getFlag("org-holdout"))
            assertEquals(true, usable.getFlag("org-holdout-zero"))
            assertEquals(0, dispatcher.flagsRequests.size)
        }
    }

    @Test
    fun `a usable string group key still evaluates and buckets on the key`() {
        withPostHog(definitions(orgFlagRollout = 50)) { postHog, dispatcher ->
            val values =
                (1..40).map { index ->
                    val snapshot = postHog.evaluateFlags("user-1", localOnly(mapOf("organization" to "org-$index")))
                    assertEquals(snapshot.isEnabled("org-flag"), snapshot.isEnabled("person-child"), "org-$index")
                    snapshot.isEnabled("org-flag")
                }
            assertEquals(setOf(true, false), values.toSet())
            assertEquals(0, dispatcher.flagsRequests.size)
        }
    }

    @Test
    fun `a usable group key without the needed group properties stays inconclusive`() {
        withPostHog(definitions()) { postHog, dispatcher ->
            val groups = mapOf("organization" to "org-1")
            assertFalse(postHog.evaluateFlags("user-1", localOnly(groups)).keys.contains("org-region"))
            assertEquals(0, dispatcher.flagsRequests.size)

            assertTrue(postHog.isFeatureEnabled("user-1", "org-region", groups = groups))
            assertEquals(1, dispatcher.flagsRequests.size)
        }
    }
}
