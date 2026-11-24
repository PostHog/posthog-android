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
        valueField: String = ", \"value\": \"pro\"",
    ): String =
        """
        {"id": 1, "key": "$key", "name": "$key", "active": true, "version": 1,
         "filters": {"groups": [{"properties": [
           {"key": "$propertyKey", "type": "person", "operator": "$operator"$valueField}
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
    fun `an unsupported operator in a cohort leaf on a listed key falls back before known absence`() {
        for (negation in listOf(false, true)) {
            val definitions =
                """
                {"flags": [
                  {"id": 1, "key": "cohort-range", "name": "cohort-range", "active": true, "version": 1,
                   "filters": {"groups": [{"properties": [
                     {"key": "id", "type": "cohort", "value": 1}
                   ], "rollout_percentage": 100}]}}
                 ],
                 "group_type_mapping": {},
                 "cohorts": {"1": {"type": "OR", "values": [{"type": "AND", "values": [
                   {"key": "plan", "type": "person", "operator": "not_between", "value": [1, 2], "negation": $negation}
                 ]}]}}}
                """.trimIndent()
            withPostHog(definitions, createMultipleFlagsResponse("cohort-range" to true)) { postHog, dispatcher ->
                val options =
                    PostHogFeatureFlagOptions.builder()
                        .personPropertyKeysKnownAbsent(listOf("plan"))
                        .build()
                assertEquals(true, postHog.getFeatureFlag("user-1", "cohort-range", options), "negation=$negation")
                assertEquals(1, dispatcher.flagsBodies.size, "negation=$negation")
                assertEquals(listOf("plan"), dispatcher.flagsBodies[0]["person_property_keys_known_absent"])
            }
        }
    }

    @Test
    fun `an omitted or null condition value on a listed key stays inconclusive and falls back to flags`() {
        val cases =
            listOf(
                "is_not" to "",
                "is_not" to ", \"value\": null",
                "exact" to ", \"value\": null",
                "gt" to ", \"value\": null",
            )
        for ((operator, valueField) in cases) {
            val label = "$operator with '$valueField'"
            withPostHog(
                createLocalEvaluationResponseFrom(personFlag("no-value", operator, valueField = valueField)),
                createMultipleFlagsResponse("no-value" to false),
            ) { postHog, dispatcher ->
                val localOnly =
                    PostHogEvaluateFlagsOptions.builder()
                        .personProperty("plan", "pro")
                        .personPropertyKeysKnownAbsent(listOf("plan"))
                        .onlyEvaluateLocally(true)
                        .build()
                val snapshot = postHog.evaluateFlags("user-1", localOnly)
                assertEquals(mapOf("no-value" to PostHogUnresolvedFlagReason.UNSUPPORTED_DEFINITION), snapshot.unresolvedFlags, label)
                assertEquals(0, dispatcher.flagsBodies.size, label)

                val options =
                    PostHogFeatureFlagOptions.builder()
                        .personPropertyKeysKnownAbsent(listOf("plan"))
                        .build()
                assertEquals(false, postHog.getFeatureFlag("user-1", "no-value", options), label)
                assertEquals(1, dispatcher.flagsBodies.size, label)
                assertEquals(listOf("plan"), dispatcher.flagsBodies[0]["person_property_keys_known_absent"], label)
            }
        }
    }

    @Test
    fun `a listed key resolves without reading a present condition value`() {
        val cases =
            listOf(
                Triple("not_regex", "\"[\"", true),
                Triple("regex", "\"[\"", false),
                Triple("not_regex", "\"\\\\p{L}\"", true),
                Triple("regex", "\"\\\\p{L}\"", false),
                Triple("gt", "\"not-a-number\"", false),
                Triple("gte", "\"not-a-number\"", false),
                Triple("lt", "\"not-a-number\"", false),
                Triple("lte", "\"not-a-number\"", false),
                Triple("semver_gt", "\"not-a-version\"", false),
                Triple("semver_eq", "\"not-a-version\"", false),
                Triple("semver_tilde", "\"not-a-version\"", false),
                Triple("semver_caret", "\"not-a-version\"", false),
                Triple("semver_wildcard", "\"not-a-version\"", false),
                Triple("is_date_before", "\"not-a-date\"", false),
                Triple("is_date_after", "\"not-a-date\"", false),
            )
        for ((operator, value, expected) in cases) {
            val label = "$operator with $value"
            withPostHog(
                createLocalEvaluationResponseFrom(personFlag("malformed", operator, valueField = ", \"value\": $value")),
                createMultipleFlagsResponse("malformed" to !expected),
            ) { postHog, dispatcher ->
                val localOnly =
                    PostHogEvaluateFlagsOptions.builder()
                        .personProperty("plan", "pro")
                        .personPropertyKeysKnownAbsent(listOf("plan"))
                        .onlyEvaluateLocally(true)
                        .build()
                val snapshot = postHog.evaluateFlags("user-1", localOnly)
                assertTrue(snapshot.unresolvedFlags.isEmpty(), label)
                assertEquals(expected, snapshot.isEnabled("malformed"), label)

                val options =
                    PostHogFeatureFlagOptions.builder()
                        .personPropertyKeysKnownAbsent(listOf("plan"))
                        .build()
                assertEquals(expected, postHog.getFeatureFlag("user-1", "malformed", options), label)
                assertEquals(0, dispatcher.flagsBodies.size, label)
            }
        }
    }

    @Test
    fun `an operator the SDK does not implement on a listed key stays unsupported and falls back to flags`() {
        val cases =
            listOf(
                "icontains_multi" to "[\"pro\"]",
                "not_icontains_multi" to "[\"pro\"]",
                "between" to "[1, 2]",
                "not_between" to "[1, 2]",
                "future_operator" to "\"pro\"",
            )
        for ((operator, value) in cases) {
            withPostHog(
                createLocalEvaluationResponseFrom(personFlag("unsupported", operator, valueField = ", \"value\": $value")),
                createMultipleFlagsResponse("unsupported" to true),
            ) { postHog, dispatcher ->
                val localOnly =
                    PostHogEvaluateFlagsOptions.builder()
                        .personPropertyKeysKnownAbsent(listOf("plan"))
                        .onlyEvaluateLocally(true)
                        .build()
                val snapshot = postHog.evaluateFlags("user-1", localOnly)
                assertEquals(mapOf("unsupported" to PostHogUnresolvedFlagReason.UNSUPPORTED_DEFINITION), snapshot.unresolvedFlags, operator)
                assertEquals(0, dispatcher.flagsBodies.size, operator)

                val options =
                    PostHogFeatureFlagOptions.builder()
                        .personPropertyKeysKnownAbsent(listOf("plan"))
                        .build()
                assertEquals(true, postHog.getFeatureFlag("user-1", "unsupported", options), operator)
                assertEquals(1, dispatcher.flagsBodies.size, operator)
                assertEquals(listOf("plan"), dispatcher.flagsBodies[0]["person_property_keys_known_absent"], operator)
            }
        }
    }

    @Test
    fun `is_set and is_not_set with a null condition value on a listed key keep their known-absent result`() {
        val definitions =
            createLocalEvaluationResponseFrom(
                personFlag("not-set", "is_not_set", valueField = ", \"value\": null"),
                personFlag("set", "is_set", valueField = ", \"value\": null").replace("\"id\": 1", "\"id\": 2"),
            )
        withPostHog(definitions) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .personProperty("plan", "pro")
                    .personPropertyKeysKnownAbsent(listOf("plan"))
                    .build()
            val snapshot = postHog.evaluateFlags("user-1", options)
            assertTrue(snapshot.isEnabled("not-set"))
            assertFalse(snapshot.isEnabled("set"))
            assertTrue(snapshot.unresolvedFlags.isEmpty())
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a listed person_metadata key never resolves from the listing`() {
        val cohortLeaf = { operator: String, value: String ->
            """{"key": "created_at", "type": "person_metadata", "operator": "$operator"$value}"""
        }
        for ((operator, value) in listOf("is_not_set" to "", "is_date_before" to ", \"value\": \"2020-01-01\"")) {
            val definitions =
                """
                {"flags": [
                  {"id": 1, "key": "new-user-only", "name": "new-user-only", "active": true, "version": 1,
                   "filters": {"groups": [{"properties": [${cohortLeaf(operator, value)}], "rollout_percentage": 100}]}},
                  {"id": 2, "key": "new-user-cohort", "name": "new-user-cohort", "active": true, "version": 1,
                   "filters": {"groups": [{"properties": [{"key": "id", "type": "cohort", "value": 1}], "rollout_percentage": 100}]}}
                 ],
                 "group_type_mapping": {},
                 "cohorts": {"1": {"type": "OR", "values": [{"type": "AND", "values": [${cohortLeaf(operator, value)}]}]}}}
                """.trimIndent()
            withPostHog(
                definitions,
                createMultipleFlagsResponse("new-user-only" to false, "new-user-cohort" to false),
            ) { postHog, dispatcher ->
                val options =
                    PostHogEvaluateFlagsOptions.builder()
                        .personPropertyKeysKnownAbsent(listOf("created_at"))
                        .build()
                val snapshot = postHog.evaluateFlags("user-1", options)
                assertFalse(snapshot.isEnabled("new-user-only"), operator)
                assertFalse(snapshot.isEnabled("new-user-cohort"), operator)
                assertEquals(1, dispatcher.flagsBodies.size, operator)
                assertEquals(listOf("created_at"), dispatcher.flagsBodies[0]["person_property_keys_known_absent"], operator)
            }
        }
    }

    @Test
    fun `a person listing does not resolve a group filter`() {
        val definitions =
            """
            {"flags": [
              {"id": 1, "key": "org-incomplete", "name": "org-incomplete", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "tier", "type": "group", "group_type_index": 0, "operator": "is_not_set"}
               ], "rollout_percentage": 100}]}},
              {"id": 2, "key": "person-flag-group-filter", "name": "person-flag-group-filter", "active": true, "version": 1,
               "filters": {"groups": [{"properties": [
                 {"key": "tier", "type": "group", "group_type_index": 0, "operator": "is_not_set"}
               ], "rollout_percentage": 100}]}}
             ],
             "group_type_mapping": {"0": "organization"},
             "cohorts": {}}
            """.trimIndent()
        withPostHog(
            definitions,
            createMultipleFlagsResponse("org-incomplete" to false, "person-flag-group-filter" to false),
        ) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .group("organization", "org-1")
                    .personPropertyKeysKnownAbsent(listOf("tier"))
                    .build()
            val snapshot = postHog.evaluateFlags("user-1", options)
            assertFalse(snapshot.isEnabled("org-incomplete"))
            assertFalse(snapshot.isEnabled("person-flag-group-filter"))
            assertEquals(1, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a group listing does not resolve a person filter or person cohort leaf on a group flag`() {
        val definitions =
            """
            {"flags": [
              {"id": 1, "key": "org-person-filter", "name": "org-person-filter", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "tier", "type": "person", "operator": "is_not_set"}
               ], "rollout_percentage": 100}]}},
              {"id": 2, "key": "org-cohort-flag", "name": "org-cohort-flag", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "id", "type": "cohort", "value": 1}
               ], "rollout_percentage": 100}]}}
             ],
             "group_type_mapping": {"0": "organization"},
             "cohorts": {"1": {"type": "OR", "values": [{"type": "AND", "values": [
               {"key": "tier", "type": "person", "operator": "is_not_set"}
             ]}]}}}
            """.trimIndent()
        withPostHog(
            definitions,
            createMultipleFlagsResponse("org-person-filter" to false, "org-cohort-flag" to false),
        ) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .group("organization", "org-1")
                    .groupPropertyKeysKnownAbsent("organization", listOf("tier"))
                    .build()
            val snapshot = postHog.evaluateFlags("user-1", options)
            assertFalse(snapshot.isEnabled("org-person-filter"))
            assertFalse(snapshot.isEnabled("org-cohort-flag"))
            assertEquals(1, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a flag dependency with a different aggregation does not use the parent's listing`() {
        val definitions =
            """
            {"flags": [
              {"id": 1, "key": "group-dep", "name": "group-dep", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "tier", "type": "group", "group_type_index": 0, "operator": "is_not_set"}
               ], "rollout_percentage": 100}]}},
              {"id": 2, "key": "person-parent", "name": "person-parent", "active": true, "version": 1,
               "filters": {"groups": [{"properties": [
                 {"key": "group-dep", "type": "flag", "operator": "flag_evaluates_to", "value": true, "dependency_chain": ["group-dep"]}
               ], "rollout_percentage": 100}]}},
              {"id": 3, "key": "person-dep", "name": "person-dep", "active": true, "version": 1,
               "filters": {"groups": [{"properties": [
                 {"key": "tier", "type": "person", "operator": "is_not_set"}
               ], "rollout_percentage": 100}]}},
              {"id": 4, "key": "group-parent", "name": "group-parent", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "person-dep", "type": "flag", "operator": "flag_evaluates_to", "value": true, "dependency_chain": ["person-dep"]}
               ], "rollout_percentage": 100}]}}
             ],
             "group_type_mapping": {"0": "organization"},
             "cohorts": {}}
            """.trimIndent()
        val remote = createMultipleFlagsResponse("person-parent" to false, "group-parent" to false)
        withPostHog(definitions, remote) { postHog, dispatcher ->
            val personListing =
                PostHogEvaluateFlagsOptions.builder()
                    .flagKeys(listOf("person-parent"))
                    .group("organization", "org-1")
                    .personPropertyKeysKnownAbsent(listOf("tier"))
                    .build()
            assertFalse(postHog.evaluateFlags("user-1", personListing).isEnabled("person-parent"))
            assertEquals(1, dispatcher.flagsBodies.size)

            val groupListing =
                PostHogEvaluateFlagsOptions.builder()
                    .flagKeys(listOf("group-parent"))
                    .group("organization", "org-1")
                    .groupPropertyKeysKnownAbsent("organization", listOf("tier"))
                    .build()
            assertFalse(postHog.evaluateFlags("user-1", groupListing).isEnabled("group-parent"))
            assertEquals(2, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a flag dependency with the same aggregation uses the listing`() {
        val definitions =
            """
            {"flags": [
              {"id": 1, "key": "org-dep", "name": "org-dep", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "tier", "type": "group", "group_type_index": 0, "operator": "is_not_set"}
               ], "rollout_percentage": 100}]}},
              {"id": 2, "key": "org-parent", "name": "org-parent", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "org-dep", "type": "flag", "operator": "flag_evaluates_to", "value": true, "dependency_chain": ["org-dep"]}
               ], "rollout_percentage": 100}]}}
             ],
             "group_type_mapping": {"0": "organization"},
             "cohorts": {}}
            """.trimIndent()
        withPostHog(definitions) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .group("organization", "org-1")
                    .groupPropertyKeysKnownAbsent("organization", listOf("tier"))
                    .build()
            assertTrue(postHog.evaluateFlags("user-1", options).isEnabled("org-parent"))
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a null key from a Java caller returns the unavailable result without evaluating or sending a request`() {
        withPostHog(
            createLocalEvaluationResponseFrom(personFlag("profile-incomplete", "is_not_set")),
            createMultipleFlagsResponse("profile-incomplete" to true),
        ) { postHog, dispatcher ->
            @Suppress("UNCHECKED_CAST")
            val withNull = java.util.Arrays.asList("plan", null) as List<String>
            val snapshot =
                postHog.evaluateFlags(
                    "user-1",
                    PostHogEvaluateFlagsOptions.builder().personPropertyKeysKnownAbsent(withNull).build(),
                )
            assertTrue(snapshot.keys.isEmpty())

            @Suppress("UNCHECKED_CAST")
            val groupWithNull = java.util.Arrays.asList("tier", null) as List<String>
            val groupSnapshot =
                postHog.evaluateFlags(
                    "user-1",
                    PostHogEvaluateFlagsOptions.builder().groupPropertyKeysKnownAbsent("organization", groupWithNull).build(),
                )
            assertTrue(groupSnapshot.keys.isEmpty())

            val flagOptions =
                PostHogFeatureFlagOptions.builder()
                    .defaultValue("fallback")
                    .personPropertyKeysKnownAbsent(withNull)
                    .build()
            assertEquals("fallback", postHog.getFeatureFlag("user-1", "profile-incomplete", flagOptions))
            assertEquals("fallback", postHog.getFeatureFlagPayload("user-1", "profile-incomplete", flagOptions))
            assertFalse(postHog.isFeatureEnabled("user-1", "profile-incomplete", flagOptions))
            assertNull(
                postHog.getFeatureFlagResult(
                    "user-1",
                    "profile-incomplete",
                    PostHogFeatureFlagResultOptions.builder().groupPropertyKeysKnownAbsent("organization", groupWithNull).build(),
                ),
            )
            assertEquals(0, dispatcher.flagsBodies.size)
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
