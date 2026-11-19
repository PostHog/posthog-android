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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
internal class PostHogKnownAbsentPropertyKeysTest {
    private class FlagsBodyDispatcher(
        private val definitions: String,
        private val flagsResponse: String,
    ) : Dispatcher() {
        val flagsBodies: MutableList<Map<String, Any?>> = CopyOnWriteArrayList()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.contains("local_evaluation") -> jsonResponse(definitions)
                path.contains("/flags") -> {
                    @Suppress("UNCHECKED_CAST")
                    flagsBodies.add(Gson().fromJson(request.body.unGzip(), Map::class.java) as Map<String, Any?>)
                    jsonResponse(flagsResponse)
                }
                else -> MockResponse().setResponseCode(200)
            }
        }
    }

    private fun withPostHog(
        definitions: String,
        flagsResponse: String = createEmptyFlagsResponse(),
        block: (PostHogInterface, FlagsBodyDispatcher) -> Unit,
    ) {
        val dispatcher = FlagsBodyDispatcher(definitions, flagsResponse)
        val mockServer = MockWebServer()
        mockServer.dispatcher = dispatcher
        mockServer.start()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_PROJECT_TOKEN)
                    .host(mockServer.url("/").toString())
                    .personalApiKey("phx_test_personal_api_key")
                    .sendFeatureFlagEvent(false)
                    .build(),
            )
        try {
            block(postHog, dispatcher)
        } finally {
            postHog.close()
            mockServer.shutdown()
        }
    }

    private fun personFlag(
        key: String,
        operator: String,
        propertyKey: String = "plan",
    ): String =
        """
        {"id": 1, "key": "$key", "name": "$key", "active": true, "version": 1,
         "filters": {"groups": [{"properties": [
           {"key": "$propertyKey", "type": "person", "operator": "$operator", "value": "pro"}
         ], "rollout_percentage": 100}]}}
        """.trimIndent()

    private fun groupDefinitions(): String =
        """
        {"flags": [
          {"id": 1, "key": "org-incomplete", "name": "org-incomplete", "active": true, "version": 1,
           "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
             {"key": "tier", "type": "group", "group_type_index": 0, "operator": "is_not_set"}
           ], "rollout_percentage": 100}]}}
         ],
         "group_type_mapping": {"0": "organization", "1": "project"},
         "cohorts": {}}
        """.trimIndent()

    @Test
    fun `a listed key matches is_not_set and overrides a supplied value without a flags request`() {
        withPostHog(createLocalEvaluationResponseFrom(personFlag("profile-incomplete", "is_not_set"))) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .personProperty("plan", "pro")
                    .personPropertyKeysKnownAbsent(listOf("plan"))
                    .build()
            assertTrue(postHog.evaluateFlags("user-1", options).isEnabled("profile-incomplete"))

            val flagOptions =
                PostHogFeatureFlagOptions.builder()
                    .personPropertyKeysKnownAbsent(listOf("plan"))
                    .build()
            assertEquals(true, postHog.getFeatureFlag("user-1", "profile-incomplete", flagOptions))
            assertTrue(postHog.isFeatureEnabled("user-1", "profile-incomplete", flagOptions))
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a listed key wins over the distinct_id the SDK adds`() {
        withPostHog(createLocalEvaluationResponseFrom(personFlag("no-distinct-id", "is_not_set", "distinct_id"))) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .personPropertyKeysKnownAbsent(listOf("distinct_id"))
                    .build()
            assertTrue(postHog.evaluateFlags("user-1", options).isEnabled("no-distinct-id"))
            assertFalse(postHog.evaluateFlags("user-1").isEnabled("no-distinct-id"))
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a group key applies only to its group type`() {
        withPostHog(groupDefinitions(), createMultipleFlagsResponse("org-incomplete" to false)) { postHog, dispatcher ->
            val listed =
                PostHogEvaluateFlagsOptions.builder()
                    .group("organization", "org-1")
                    .groupPropertyKeysKnownAbsent("organization", listOf("tier"))
                    .build()
            assertTrue(postHog.evaluateFlags("user-1", listed).isEnabled("org-incomplete"))
            assertEquals(0, dispatcher.flagsBodies.size)

            val otherType =
                PostHogEvaluateFlagsOptions.builder()
                    .group("organization", "org-1")
                    .groupPropertyKeysKnownAbsent("project", listOf("tier"))
                    .build()
            assertFalse(postHog.evaluateFlags("user-1", otherType).isEnabled("org-incomplete"))
            assertEquals(1, dispatcher.flagsBodies.size)
            assertEquals(mapOf("project" to listOf("tier")), dispatcher.flagsBodies[0]["group_property_keys_known_absent"])
        }
    }

    @Test
    fun `remote fallback sends the listed keys and omits them when none were provided`() {
        val definitions =
            createLocalEvaluationResponseFrom(personFlag("profile-incomplete", "is_not_set"), emailGatedFlagDefinition("gated"))
        withPostHog(definitions, createMultipleFlagsResponse("gated" to true)) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .personPropertyKeysKnownAbsent(listOf("plan", "plan", "__posthog_person_metadata__created_at"))
                    .groupPropertyKeysKnownAbsent(mapOf("organization" to listOf("tier"), "project" to emptyList()))
                    .build()
            val snapshot = postHog.evaluateFlags("user-1", options)
            assertTrue(snapshot.isEnabled("profile-incomplete"))
            assertTrue(snapshot.isEnabled("gated"))

            postHog.evaluateFlags("user-2")
            postHog.evaluateFlags("user-3", PostHogEvaluateFlagsOptions.builder().personPropertyKeysKnownAbsent(emptyList()).build())

            assertEquals(3, dispatcher.flagsBodies.size)
            assertEquals(listOf("plan"), dispatcher.flagsBodies[0]["person_property_keys_known_absent"])
            assertEquals(mapOf("organization" to listOf("tier")), dispatcher.flagsBodies[0]["group_property_keys_known_absent"])
            for (body in dispatcher.flagsBodies.drop(1)) {
                assertFalse(body.containsKey("person_property_keys_known_absent"))
                assertFalse(body.containsKey("group_property_keys_known_absent"))
            }
        }
    }

    @Test
    fun `flag getters send the listed keys on fallback and cache them separately`() {
        withPostHog(createLocalEvaluationResponseFrom(emailGatedFlagDefinition("gated")), createMultipleFlagsResponse("gated" to true)) {
                postHog,
                dispatcher,
            ->
            val options =
                PostHogFeatureFlagResultOptions.builder()
                    .personPropertyKeysKnownAbsent(listOf("plan"))
                    .build()
            assertEquals(true, postHog.getFeatureFlagResult("user-1", "gated", options)?.value)
            assertEquals(true, postHog.getFeatureFlag("user-1", "gated"))

            assertEquals(2, dispatcher.flagsBodies.size, "the listing is part of the cache key")
            assertEquals(listOf("plan"), dispatcher.flagsBodies[0]["person_property_keys_known_absent"])
            assertFalse(dispatcher.flagsBodies[1].containsKey("person_property_keys_known_absent"))
        }
    }

    @Test
    fun `an unsupported operator on a listed key still falls back to flags`() {
        withPostHog(
            createLocalEvaluationResponseFrom(personFlag("multi", "not_icontains_multi")),
            createMultipleFlagsResponse("multi" to false),
        ) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .personPropertyKeysKnownAbsent(listOf("plan"))
                    .build()
            assertFalse(postHog.evaluateFlags("user-1", options).isEnabled("multi"))
            assertEquals(1, dispatcher.flagsBodies.size)
            assertEquals(listOf("plan"), dispatcher.flagsBodies[0]["person_property_keys_known_absent"])
        }
    }

    @Test
    fun `more than 1000 keys returns the unavailable result without evaluating or sending a request`() {
        withPostHog(
            createLocalEvaluationResponseFrom(personFlag("profile-incomplete", "is_not_set")),
            createMultipleFlagsResponse("profile-incomplete" to true),
        ) { postHog, dispatcher ->
            val tooMany = List(1001) { "plan" }
            val snapshot =
                postHog.evaluateFlags(
                    "user-1",
                    PostHogEvaluateFlagsOptions.builder().personPropertyKeysKnownAbsent(tooMany).build(),
                )
            assertTrue(snapshot.keys.isEmpty())

            val groupSnapshot =
                postHog.evaluateFlags(
                    "user-1",
                    PostHogEvaluateFlagsOptions.builder().groupPropertyKeysKnownAbsent("organization", List(1001) { "tier" }).build(),
                )
            assertTrue(groupSnapshot.keys.isEmpty())

            val flagOptions =
                PostHogFeatureFlagOptions.builder()
                    .defaultValue("fallback")
                    .personPropertyKeysKnownAbsent(tooMany)
                    .build()
            assertEquals("fallback", postHog.getFeatureFlag("user-1", "profile-incomplete", flagOptions))
            assertEquals("fallback", postHog.getFeatureFlagPayload("user-1", "profile-incomplete", flagOptions))
            assertFalse(postHog.isFeatureEnabled("user-1", "profile-incomplete", flagOptions))
            assertNull(
                postHog.getFeatureFlagResult(
                    "user-1",
                    "profile-incomplete",
                    PostHogFeatureFlagResultOptions.builder().personPropertyKeysKnownAbsent(tooMany).build(),
                ),
            )
            assertEquals(0, dispatcher.flagsBodies.size)

            val atLimit =
                postHog.evaluateFlags(
                    "user-1",
                    PostHogEvaluateFlagsOptions.builder().personPropertyKeysKnownAbsent(List(1000) { "plan" }).build(),
                )
            assertTrue(atLimit.isEnabled("profile-incomplete"))
        }
    }
}
